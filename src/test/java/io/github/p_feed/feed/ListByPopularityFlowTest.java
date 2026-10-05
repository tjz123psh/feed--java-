package io.github.p_feed.feed;

import io.github.p_feed.ApiApplication;
import io.github.p_feed.feed.dto.FeedVideoItem;
import io.github.p_feed.feed.dto.ListByPopularityRequest;
import io.github.p_feed.feed.dto.ListByPopularityResponse;
import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.infra.RedisKeys;
import io.github.p_feed.video.Video;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 10 步热榜五条契约：① 同一 as_of 的第一页被快照冻结；② 窗口只覆盖最近 60 分钟；
 * ③ offset 越界的空页不进 DB 且 as_of 保持真值；④ 首页空 → DB 兜底（as_of=0 + 三元游标取末行）；
 * ⑤ 两条游标校验的 400 文案。
 * FeedMapper 用 @MockitoBean 替掉，"有没有落 DB / 落了几次"才能被 verify 数出来；窗口与快照走真 Redis。
 * 基准分钟在 @BeforeEach 固定，避免用例跨分钟抖动；时间断言比 toInstant()。
 */
@SpringBootTest(classes = ApiApplication.class)
class ListByPopularityFlowTest {

    private static final long PROBE_ID_BASE = 9_850_000_000L;
    private static final long PROBE_ID_RANGE = 1_000_000_000L;
    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);

    @Autowired private FeedService feedService;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisKeys keys;

    @MockitoBean private FeedMapper feedMapper;

    private OffsetDateTime asOfMinute;

    @BeforeEach
    void setUp() {
        asOfMinute = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MINUTES);
        cleanHotKeys();
    }

    @AfterEach
    void tearDown() {
        cleanHotKeys();
    }

    @Test
    void snapshotFreezesTheFirstPage() {
        Video a = sampleVideo(newId(), 5L);
        Video b = sampleVideo(newId(), 3L);
        Video c = sampleVideo(newId(), 99L);
        seedWindow(asOfMinute, a.getId(), 5);
        seedWindow(asOfMinute, b.getId(), 3);
        // 只打一次桩：再次 when(...) 时 Mockito 会用 any() 的占位参数（null）真调用一次现有 answer → NPE
        when(feedMapper.listByIds(any())).thenAnswer(inv -> matching(inv.getArgument(0), List.of(a, b, c)));

        ListByPopularityRequest firstPage =
                new ListByPopularityRequest(2, asOfMinute.toEpochSecond(), 0, 0L, null, null);
        ListByPopularityResponse first = feedService.listByPopularity(firstPage, 0L);

        assertThat(first.asOf()).isEqualTo(asOfMinute.toEpochSecond());
        assertThat(first.videoList()).extracting(FeedVideoItem::id).containsExactly(a.getId(), b.getId());
        assertThat(first.nextOffset()).isEqualTo(2);
        assertThat(first.hasMore()).isTrue();

        // 窗口里追加一条 99 分的新视频：快照已冻结，第一页不能变（answer 只按被请求的 id 返回，c 不会被带出来）
        seedWindow(asOfMinute, c.getId(), 99);

        ListByPopularityResponse again = feedService.listByPopularity(firstPage, 0L);

        assertThat(again.videoList()).extracting(FeedVideoItem::id).containsExactly(a.getId(), b.getId());
        Long ttl = redis.getExpire(snapshotKey(asOfMinute), TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isBetween(100L, 120L);
        verify(feedMapper, never()).listByPopularity(anyInt(), any(), any(), any());
    }

    @Test
    void windowCoversLast60Minutes() {
        Video inWindow = sampleVideo(newId(), 10L); // 30 分钟前：在 60 个窗口内
        Video outWindow = sampleVideo(newId(), 99L); // 60 分钟前：恰好滑出窗口
        seedWindow(asOfMinute.minusMinutes(30), inWindow.getId(), 10);
        seedWindow(asOfMinute.minusMinutes(60), outWindow.getId(), 99);
        when(feedMapper.listByIds(any()))
                .thenAnswer(inv -> matching(inv.getArgument(0), List.of(inWindow, outWindow)));

        ListByPopularityResponse page = feedService.listByPopularity(
                new ListByPopularityRequest(10, asOfMinute.toEpochSecond(), 0, 0L, null, null), 0L);

        assertThat(page.videoList()).extracting(FeedVideoItem::id).containsExactly(inWindow.getId());
    }

    @Test
    void offsetBeyondSnapshot_returnsEmptyPageWithoutDb() {
        ListByPopularityResponse page = feedService.listByPopularity(
                new ListByPopularityRequest(3, asOfMinute.toEpochSecond(), 10, 0L, null, null), 0L);

        assertThat(page.videoList()).isEmpty();
        assertThat(page.asOf()).isEqualTo(asOfMinute.toEpochSecond()); // 真值，不是 0
        assertThat(page.nextOffset()).isEqualTo(10);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextLatestPopularity()).isNull();
        assertThat(page.nextLatestBefore()).isNull();
        assertThat(page.nextLatestIdBefore()).isNull();
        verify(feedMapper, never()).listByPopularity(anyInt(), any(), any(), any());
    }

    @Test
    void snapshotIdMissingInDb_isSkippedNotAddedAsNull() {
        Video a = sampleVideo(newId(), 10L);
        Video gone = sampleVideo(newId(), 9L); // 快照里有、DB 里已经没有了（视频被删）
        seedWindow(asOfMinute, a.getId(), 10);
        seedWindow(asOfMinute, gone.getId(), 9);
        // DB 只回得来 a：gone 必须被静默跳过，而不是把 null 塞进结果让下游 NPE
        when(feedMapper.listByIds(any())).thenAnswer(inv -> matching(inv.getArgument(0), List.of(a)));

        ListByPopularityResponse page = feedService.listByPopularity(
                new ListByPopularityRequest(3, asOfMinute.toEpochSecond(), 0, 0L, null, null), 0L);

        assertThat(page.videoList()).extracting(FeedVideoItem::id).containsExactly(a.getId());
        assertThat(page.nextOffset()).isEqualTo(1); // offset + len(items)，不是 offset+limit
        assertThat(page.hasMore()).isFalse(); // 1 < 3
        assertThat(page.asOf()).isEqualTo(asOfMinute.toEpochSecond());
    }

    @Test
    void firstPageEmpty_fallsBackToDb() {
        Video a = sampleVideo(newId(), 10L);
        Video b = sampleVideo(newId(), 4L);
        when(feedMapper.listByPopularity(eq(3), isNull(), isNull(), isNull())).thenReturn(List.of(a, b));

        ListByPopularityResponse page = feedService.listByPopularity(
                new ListByPopularityRequest(3, 0L, 0, 0L, null, null), 0L);

        assertThat(page.asOf()).isZero(); // 0 = 我在走兜底
        assertThat(page.nextOffset()).isZero();
        assertThat(page.hasMore()).isFalse(); // 2 < 3
        assertThat(page.videoList()).extracting(FeedVideoItem::id).containsExactly(a.getId(), b.getId());
        assertThat(page.nextLatestPopularity()).isEqualTo(b.getPopularity());
        assertThat(page.nextLatestBefore().toInstant()).isEqualTo(b.getCreateTime().toInstant());
        assertThat(page.nextLatestIdBefore()).isEqualTo(b.getId());
        verify(feedMapper, times(1)).listByPopularity(eq(3), isNull(), isNull(), isNull());
    }

    @Test
    void invalidCursors_throw400() {
        assertThatThrownBy(() -> feedService.listByPopularity(
                        new ListByPopularityRequest(3, 0L, 0, -1L, null, null), 0L))
                .isInstanceOf(ApiException.class)
                .hasMessage(Errors.LATEST_POPULARITY_MUST_BE_NON_NEGATIVE);

        assertThatThrownBy(() -> feedService.listByPopularity(
                        new ListByPopularityRequest(3, 0L, 0, 0L, OffsetDateTime.now(ZoneOffset.UTC), null), 0L))
                .isInstanceOf(ApiException.class)
                .hasMessage(Errors.LATEST_BEFORE_AND_ID_MUST_BE_TOGETHER);
    }

    private long newId() {
        return PROBE_ID_BASE + ThreadLocalRandom.current().nextLong(PROBE_ID_RANGE);
    }

    private void seedWindow(OffsetDateTime minute, long videoId, double score) {
        redis.opsForZSet().incrementScore(windowKey(minute), String.valueOf(videoId), score);
    }

    private String windowKey(OffsetDateTime minute) {
        return keys.key("hot:video:1m:%s", MINUTE.format(minute));
    }

    private String snapshotKey(OffsetDateTime minute) {
        return keys.key("hot:video:merge:1m:%s", MINUTE.format(minute));
    }

    /** 删掉基准分钟前后各 61 个窗口键与两把快照键，避免跨用例串味 */
    private void cleanHotKeys() {
        List<String> hotKeys = new ArrayList<>(124);
        for (int i = -61; i <= 61; i++) {
            hotKeys.add(windowKey(asOfMinute.minusMinutes(i)));
        }
        hotKeys.add(snapshotKey(asOfMinute));
        hotKeys.add(snapshotKey(asOfMinute.plusMinutes(1)));
        hotKeys.add(snapshotKey(asOfMinute.minusMinutes(1)));
        redis.delete(hotKeys);
    }

    private Video sampleVideo(long id, long popularity) {
        Video video = new Video();
        video.setId(id);
        video.setAuthorId(9_900_000_001L);
        video.setUsername("step10_probe");
        video.setTitle("step10-" + id);
        video.setDescription("hot ranking probe");
        video.setPlayUrl("http://localhost:8081/static/videos/probe.mp4");
        video.setCoverUrl("http://localhost:8081/static/covers/probe.png");
        video.setCreateTime(OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(1_790_000_000L + (id % 1_000)), ZoneOffset.UTC));
        video.setLikesCount(popularity);
        video.setPopularity(popularity);
        return video;
    }

    private static List<Video> matching(List<Long> asked, List<Video> rows) {
        List<Video> hit = new ArrayList<>();
        for (Video row : rows) {
            if (asked.contains(row.getId())) {
                hit.add(row);
            }
        }
        return hit;
    }
}
