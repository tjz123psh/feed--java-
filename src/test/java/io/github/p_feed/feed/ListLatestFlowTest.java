package io.github.p_feed.feed;

import io.github.p_feed.ApiApplication;
import io.github.p_feed.feed.dto.FeedVideoItem;
import io.github.p_feed.feed.dto.ListLatestResponse;
import io.github.p_feed.infra.RedisKeys;
import io.github.p_feed.video.Video;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 9 步推荐流四条契约：① 热路径只读 Redis、不打 DB；② 冷路径查 DB 且不写 ZSET；
 * ③ 窗口为空时用 DB 重建、且只重建一次；④ 热数据不足 N 条时用末条时间补 DB（stitch）。
 * FeedMapper 用 @MockitoBean 替掉，"该不该打库、打了几次"才能被 verify 精确数出来；时间线走真 Redis。
 * 时间断言一律比 toInstant()：Jackson 3 读回 OffsetDateTime 会归一化成 UTC，比对象必假。
 */
@SpringBootTest(classes = ApiApplication.class)
class ListLatestFlowTest {

    private static final long PROBE_ID_BASE = 9_800_000_000L;
    private static final long PROBE_ID_RANGE = 1_000_000_000L;
    private static final long T0 = 1_760_000_000_000L; // 固定基准毫秒，避免用例间漂移

    @Autowired private FeedService feedService;
    @Autowired private VideoEntityCache videoEntityCache;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisKeys keys;

    @MockitoBean private FeedMapper feedMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Long> createdIds = new ArrayList<>();

    @BeforeEach
    void resetCaches() {
        videoEntityCache.invalidateAll();
        redis.delete(timelineKey());
    }

    @AfterEach
    void cleanUp() {
        if (!createdIds.isEmpty()) {
            redis.delete(createdIds.stream().map(id -> keys.key("video:entity:%d", id)).toList());
        }
        redis.delete(timelineKey());
        videoEntityCache.invalidateAll();
    }

    @Test
    void hotWindow_servesFromRedis() throws Exception {
        Video a = sampleVideo(newId(), T0);
        Video b = sampleVideo(newId(), T0 + 1_000);
        Video c = sampleVideo(newId(), T0 + 2_000);
        seedEntityCache(a, b, c);
        redis.opsForZSet().add(timelineKey(), tuples(List.of(a, b, c)));

        ListLatestResponse resp = feedService.listLatest(3, null, 0L);

        assertThat(resp.videoList())
                .extracting(FeedVideoItem::id)
                .containsExactly(c.getId(), b.getId(), a.getId());
        assertThat(resp.nextTime()).isEqualTo(T0); // 末条 = 最老的 a
        assertThat(resp.hasMore()).isTrue();
        verify(feedMapper, never()).listLatest(anyInt(), any());
        verify(feedMapper, never()).listByIds(any());
    }

    @Test
    void coldCursor_queriesDb() {
        Video hot = sampleVideo(newId(), T0);
        redis.opsForZSet().add(timelineKey(), tuples(List.of(hot)));
        Video c1 = sampleVideo(newId(), T0 - 5_000);
        Video c2 = sampleVideo(newId(), T0 - 4_000);
        when(feedMapper.listLatest(eq(3), any())).thenReturn(List.of(c2, c1)); // 新在前

        ListLatestResponse resp = feedService.listLatest(3, T0 - 1, 0L); // 游标 ≤ watermark

        assertThat(resp.videoList())
                .extracting(FeedVideoItem::id)
                .containsExactly(c2.getId(), c1.getId());
        assertThat(resp.hasMore()).isFalse(); // DB 只回 2 条 < limit=3 → 不满页
        assertThat(resp.nextTime()).isEqualTo(T0 - 5_000); // 末条 = 最老的 c1
        assertThat(redis.opsForZSet().zCard(timelineKey())).isEqualTo(1L);
        assertThat(redis.opsForZSet().score(timelineKey(), String.valueOf(c2.getId()))).isNull();
        verify(feedMapper, times(1)).listLatest(eq(3), any());
    }

    @Test
    void emptyWindow_rebuildsOnce() {
        Video a = sampleVideo(newId(), T0);
        Video b = sampleVideo(newId(), T0 + 1_000);
        when(feedMapper.listLatest(eq(1000), isNull())).thenReturn(List.of(b, a));
        when(feedMapper.listByIds(any())).thenAnswer(inv -> matching(inv.getArgument(0), List.of(a, b)));

        ListLatestResponse first = feedService.listLatest(2, null, 0L);

        assertThat(first.videoList())
                .extracting(FeedVideoItem::id)
                .containsExactly(b.getId(), a.getId());
        assertThat(redis.opsForZSet().zCard(timelineKey())).isEqualTo(2L);
        assertThat(redis.opsForZSet().score(timelineKey(), String.valueOf(a.getId())))
                .isEqualTo((double) T0); // score = 发布毫秒

        ListLatestResponse second = feedService.listLatest(2, null, 0L);

        assertThat(second.videoList())
                .extracting(FeedVideoItem::id)
                .containsExactly(b.getId(), a.getId());
        verify(feedMapper, times(1)).listLatest(eq(1000), isNull()); // 重建只发生一次
    }

    @Test
    void hotShortfall_stitchesFromDb() throws Exception {
        Video hot = sampleVideo(newId(), T0 + 5_000);
        seedEntityCache(hot);
        redis.opsForZSet().add(timelineKey(), tuples(List.of(hot)));
        Video older1 = sampleVideo(newId(), T0 + 1_000);
        Video older2 = sampleVideo(newId(), T0);
        when(feedMapper.listLatest(eq(2), any())).thenReturn(List.of(older1, older2));

        ListLatestResponse resp = feedService.listLatest(3, null, 0L);

        assertThat(resp.videoList())
                .extracting(FeedVideoItem::id)
                .containsExactly(hot.getId(), older1.getId(), older2.getId());
        assertThat(resp.hasMore()).isTrue();

        ArgumentCaptor<OffsetDateTime> cursor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(feedMapper, times(1)).listLatest(eq(2), cursor.capture());
        assertThat(cursor.getValue().toInstant()).isEqualTo(hot.getCreateTime().toInstant());
    }

    private long newId() {
        long id = PROBE_ID_BASE + ThreadLocalRandom.current().nextLong(PROBE_ID_RANGE);
        createdIds.add(id);
        return id;
    }

    private String timelineKey() {
        return keys.key("feed:global_timeline");
    }

    private Video sampleVideo(long id, long millis) {
        Video video = new Video();
        video.setId(id);
        video.setAuthorId(9_900_000_001L);
        video.setUsername("step9_probe");
        video.setTitle("step9-" + id);
        video.setDescription("listLatest probe");
        video.setPlayUrl("http://localhost:8081/static/videos/probe.mp4");
        video.setCoverUrl("http://localhost:8081/static/covers/probe.png");
        video.setCreateTime(OffsetDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneOffset.UTC));
        video.setLikesCount(0L);
        video.setPopularity(0L);
        return video;
    }

    private void seedEntityCache(Video... videos) throws Exception {
        for (Video video : videos) {
            redis.opsForValue()
                    .set(
                            keys.key("video:entity:%d", video.getId()),
                            objectMapper.writeValueAsString(video),
                            Duration.ofHours(1));
        }
    }

    private static Set<ZSetOperations.TypedTuple<String>> tuples(List<Video> videos) {
        Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
        for (Video video : videos) {
            tuples.add(
                    ZSetOperations.TypedTuple.of(
                            String.valueOf(video.getId()),
                            (double) video.getCreateTime().toInstant().toEpochMilli()));
        }
        return tuples;
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
