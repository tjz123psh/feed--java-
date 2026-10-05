package io.github.p_feed.feed;

import io.github.p_feed.ApiApplication;
import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.video.TagMapper;
import io.github.p_feed.video.Video;
import io.github.p_feed.video.VideoMapper;
import io.github.p_feed.video.VideoTag;
import io.github.p_feed.video.VideoTagMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 * 第 5 步验收：三种游标各翻页"不重不漏"。
 * 数据：A 组 30 条探针（create_time 互不相同、likes_count 互不相同、popularity 每 3 条并列）
 *      + B 组 2 条（popularity 与 create_time 都完全相同，只能靠主键 id 兜底）。
 * 注意：纯时间游标（A 组断言）不追加 id，同时间的行可能丢——这是原版取舍，
 * 所以 B 组只参与三元游标的断言。
 */
@SpringBootTest(classes = ApiApplication.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeedCursorTest {

    private static final long PROBE_AUTHOR = 9_900_000_001L;
    private static final String PROBE_TAG = "cursor_probe_tag";
    private static final int N = 30;

    @Autowired private FeedMapper feedMapper;
    @Autowired private FeedService feedService;
    @Autowired private VideoMapper videoMapper;
    @Autowired private VideoTagMapper videoTagMapper;
    @Autowired private TagMapper tagMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final List<Long> probeIds = new ArrayList<>();   // A 组
    private final List<Long> tieIds = new ArrayList<>();     // B 组（双键并列）

    @BeforeAll
    void seed() {
        OffsetDateTime base = OffsetDateTime.now().minusDays(1).truncatedTo(ChronoUnit.MILLIS);
        for (int i = 1; i <= N; i++) {
            Video v = probe(i, base.plusSeconds(i), (long) i, (long) (i / 3));
            probeIds.add(v.getId());
        }
        // B 组：popularity=10（与 A 组 i=28..30 并列）、create_time 完全相同 → 三元游标必须靠 id 兜底
         OffsetDateTime tieTime = base.plusSeconds(30).plus(500, ChronoUnit.MILLIS);
        for (int k = 0; k < 2; k++) {
            Video v = probe(100 + k, tieTime, 100L + k, 10L);
            tieIds.add(v.getId());
        }

        // 给 A 组前 3 条打上探针话题（listByTag 用）
        tagMapper.insertIgnore(PROBE_TAG);
        Long tagId = jdbcTemplate.queryForObject("select id from tags where name = ?", Long.class, PROBE_TAG);
        for (int i = 0; i < 3; i++) {
            VideoTag vt = new VideoTag();
            vt.setVideoId(probeIds.get(i));
            vt.setTagId(tagId);
            videoTagMapper.insert(vt);
        }
    }

    @AfterAll
    void cleanup() {
        jdbcTemplate.update("delete from video_tags where video_id in (select id from videos where author_id = ?)", PROBE_AUTHOR);
        jdbcTemplate.update("delete from tags where name = ?", PROBE_TAG);
        jdbcTemplate.update("delete from videos where author_id = ?", PROBE_AUTHOR);
    }

    private Video probe(int seq, OffsetDateTime time, long likes, long popularity) {
        Video v = new Video();
        v.setAuthorId(PROBE_AUTHOR);
        v.setUsername("cursor_probe");
        v.setTitle("probe " + seq);
        v.setDescription("probe");
        v.setPlayUrl("http://localhost/static/probe/p.mp4");
        v.setCoverUrl("http://localhost/static/probe/c.jpg");
        v.setCreateTime(time);
        v.setLikesCount(likes);
        v.setPopularity(popularity);
        videoMapper.insert(v);
        return v;
    }

    // ---------- 三种游标各翻页 ----------

    @ParameterizedTest
    @ValueSource(ints = {10, 7})
    void timeCursor_pagesWithoutDupOrLoss(int pageSize) {
        List<Video> all = new ArrayList<>();
        OffsetDateTime cursor = null;
        for (int guard = 0; guard < 50; guard++) {
            List<Video> page = feedMapper.listLatest(pageSize, cursor);
            if (page.isEmpty()) break;
            all.addAll(page);
            cursor = page.get(page.size() - 1).getCreateTime();
            if (page.size() < pageSize) break;
        }
        assertThat(all).isSortedAccordingTo(
                Comparator.comparing(Video::getCreateTime, Comparator.<OffsetDateTime>reverseOrder()));
        assertExactlyOnce(all, probeIds);
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 7})
    void likesCountCursor_pagesWithoutDupOrLoss(int pageSize) {
        List<Video> all = new ArrayList<>();
        Long likes = null, id = null;
        for (int guard = 0; guard < 50; guard++) {
            List<Video> page = feedMapper.listLikesCountWithCursor(pageSize, likes, id);
            if (page.isEmpty()) break;
            all.addAll(page);
            Video last = page.get(page.size() - 1);
            likes = last.getLikesCount();
            id = last.getId();
            if (page.size() < pageSize) break;
        }
        assertThat(all).isSortedAccordingTo(
                Comparator.comparing(Video::getLikesCount, Comparator.<Long>reverseOrder())
                        .thenComparing(Video::getId, Comparator.<Long>reverseOrder()));
        assertExactlyOnce(all, probeIds);
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 7})
    void popularityCursor_pagesWithoutDupOrLoss(int pageSize) {
        List<Video> all = new ArrayList<>();
        Long pop = null, id = null;
        OffsetDateTime time = null;
        for (int guard = 0; guard < 50; guard++) {
            List<Video> page = feedMapper.listByPopularity(pageSize, pop, time, id);
            if (page.isEmpty()) break;
            all.addAll(page);
            Video last = page.get(page.size() - 1);
            pop = last.getPopularity();
            time = last.getCreateTime();
            id = last.getId();
            if (page.size() < pageSize) break;
        }
        assertThat(all).isSortedAccordingTo(
                Comparator.comparing(Video::getPopularity, Comparator.<Long>reverseOrder())
                        .thenComparing(Video::getCreateTime, Comparator.<OffsetDateTime>reverseOrder())
                        .thenComparing(Video::getId, Comparator.<Long>reverseOrder()));
        List<Long> expected = new ArrayList<>(probeIds);
        expected.addAll(tieIds);          // 三元游标连"双键并列"也必须不重不漏
        assertExactlyOnce(all, expected);
    }

    // ---------- listByTag ----------

    @Test
    void listByTag_returnsTaggedVideosDescByTime_andEmptyForUnknownTag() {
        List<Video> byTag = feedMapper.listByTag(PROBE_TAG, 10);
        assertThat(byTag).hasSize(3);
        assertThat(byTag).extracting(Video::getId)
                .containsExactlyInAnyOrderElementsOf(probeIds.subList(0, 3));
        assertThat(byTag).isSortedAccordingTo(
                Comparator.comparing(Video::getCreateTime, Comparator.<OffsetDateTime>reverseOrder()));
        assertThat(feedMapper.listByTag("no_such_tag_zzz", 10)).isEmpty();
    }

    @Test
    void listByTag_serviceRejectsBlankTag_andNormalizesLimit() {
        assertThatThrownBy(() -> feedService.listByTag("", 10, 0L))
                .isInstanceOf(ApiException.class)
                .hasMessage(Errors.TAG_NAME_IS_REQUIRED);
        assertThat(FeedService.normalizeLimit(0)).isEqualTo(10);
        assertThat(FeedService.normalizeLimit(51)).isEqualTo(10);
        assertThat(FeedService.normalizeLimit(50)).isEqualTo(50);
    }

    private void assertExactlyOnce(List<Video> all, List<Long> expectedIds) {
        List<Long> got = all.stream().map(Video::getId).filter(expectedIds::contains).toList();
        assertThat(got).as("无漏：全部翻到").hasSize(expectedIds.size());
        assertThat(got).as("无重：每条只出现一次").doesNotHaveDuplicates();
    }
}
