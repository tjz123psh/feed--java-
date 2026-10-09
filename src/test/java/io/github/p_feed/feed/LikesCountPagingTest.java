package io.github.p_feed.feed;

import io.github.p_feed.ApiApplication;
import io.github.p_feed.feed.dto.FeedVideoItem;
import io.github.p_feed.feed.dto.LikesCountResponse;
import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.video.Video;
import io.github.p_feed.video.VideoMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 第 6 步验收：点赞榜的二元复合游标 (likes_count, id)。
 * 覆盖：跨页不重不漏 + 有序、next_* 等于本页最后一条、has_more 语义、
 * 四条游标校验文案、两个 0 = 第一页、空页时 next_* 键不出现。
 */
@SpringBootTest(classes = ApiApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LikesCountPagingTest {

    private static final long PROBE_AUTHOR = 9_900_000_002L;
    private static final int N = 30;

    @Autowired private FeedService feedService;
    @Autowired private VideoMapper videoMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> probeIds = new ArrayList<>();

    @BeforeAll
    void seed() {
        OffsetDateTime base = OffsetDateTime.now().minusDays(2).truncatedTo(ChronoUnit.MILLIS);
        for (int i = 1; i <= N; i++) {
            Video v = new Video();
            v.setAuthorId(PROBE_AUTHOR);
            v.setUsername("likes_probe");
            v.setTitle("likes probe " + i);
            v.setDescription("probe");
            v.setPlayUrl("http://localhost/static/probe/p.mp4");
            v.setCoverUrl("http://localhost/static/probe/c.jpg");
            v.setCreateTime(base.plusSeconds(i));
            v.setLikesCount((long) i);      // 1..30 互不相同：每页 10 条能翻 3 页
            v.setPopularity((long) i);
            videoMapper.insert(v);
            probeIds.add(v.getId());
        }
    }

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("delete from videos where author_id = ?", PROBE_AUTHOR);
    }

    /** 一路按 next_* 翻到底：不重不漏 + 有序 + next_* 与页尾一致 + 末页 has_more=false */
    @Test
    void pagesWithCursor_withoutDupOrLoss() {
        int limit = 10;
        List<FeedVideoItem> all = new ArrayList<>();
        Long likes = null;
        Long id = null;
        boolean hasMore = true;
        int pages = 0;

        while (hasMore && pages < 10) {                       // 上限防死循环
            LikesCountResponse page = feedService.listLikesCount(limit, likes, id, 0L);
            pages++;
            List<FeedVideoItem> items = page.videoList();
            if (items.isEmpty()) {
                break;
            }
            // 每页的 next_* 必须等于本页最后一条（续翻全靠它）
            FeedVideoItem last = items.get(items.size() - 1);
            assertThat(page.nextLikesCountBefore()).isEqualTo(last.likesCount());
            assertThat(page.nextIdBefore()).isEqualTo(last.id());

            all.addAll(items);
            likes = page.nextLikesCountBefore();
            id = page.nextIdBefore();
            hasMore = page.hasMore();
        }

        assertThat(pages).as("至少翻 3 页").isGreaterThanOrEqualTo(3);
        assertThat(hasMore).as("最后一页 has_more=false").isFalse();

        // 整串按 (likes_count DESC, id DESC) 单调有序——跨页也不许乱
        assertThat(all).isSortedAccordingTo(
                Comparator.comparingLong(FeedVideoItem::likesCount).reversed()
                        .thenComparing(Comparator.comparingLong(FeedVideoItem::id).reversed()));

        // 30 条探针一条不漏、一条不重
        List<Long> got = all.stream().map(FeedVideoItem::id).filter(probeIds::contains).toList();
        assertThat(got).as("无漏：30 条探针全部翻到").hasSize(N);
        assertThat(got).as("无重：每条只出现一次").doesNotHaveDuplicates();
    }

    /** 四条校验文案逐字 + 两个 0 = 第一页（结果与不传游标一致） */
    @Test
    void cursorValidation_messagesAreExact() {
        // 只传一个（两个方向都测）
        assertThatThrownBy(() -> feedService.listLikesCount(10, 5L, null, 0L))
                .isInstanceOf(ApiException.class)
                .hasMessage(Errors.LIKES_COUNT_AND_ID_MUST_BE_TOGETHER);
        assertThatThrownBy(() -> feedService.listLikesCount(10, null, 5L, 0L))
                .isInstanceOf(ApiException.class)
                .hasMessage(Errors.LIKES_COUNT_AND_ID_MUST_BE_TOGETHER);

        // likes_count_before < 0
        assertThatThrownBy(() -> feedService.listLikesCount(10, -1L, 5L, 0L))
                .isInstanceOf(ApiException.class)
                .hasMessage(Errors.INVALID_CURSOR_LIKES_COUNT_BEFORE);

        // id_before <= 0 且 likes 非 0
        assertThatThrownBy(() -> feedService.listLikesCount(10, 5L, 0L, 0L))
                .isInstanceOf(ApiException.class)
                .hasMessage(Errors.INVALID_CURSOR_ID_BEFORE);
        assertThatThrownBy(() -> feedService.listLikesCount(10, 5L, -3L, 0L))
                .isInstanceOf(ApiException.class)
                .hasMessage(Errors.INVALID_CURSOR_ID_BEFORE);

        // 两个都为 0：不报错，且结果与第一页完全相同
        LikesCountResponse withZeros = feedService.listLikesCount(10, 0L, 0L, 0L);
        LikesCountResponse firstPage = feedService.listLikesCount(10, null, null, 0L);
        assertThat(withZeros.videoList()).extracting(FeedVideoItem::id)
                .isEqualTo(firstPage.videoList().stream().map(FeedVideoItem::id).toList());
        assertThat(withZeros.nextLikesCountBefore()).isEqualTo(firstPage.nextLikesCountBefore());
        assertThat(withZeros.nextIdBefore()).isEqualTo(firstPage.nextIdBefore());
    }

    /** 空页：next_* 两个键不出现 */
    @Test
    void emptyPage_omitsNextCursorKeys() throws Exception {
        String json = new tools.jackson.databind.ObjectMapper().writeValueAsString(
                new LikesCountResponse(List.of(), null, null, false));
        assertThat(json).isEqualTo("{\"video_list\":[],\"has_more\":false}");
    }
}
