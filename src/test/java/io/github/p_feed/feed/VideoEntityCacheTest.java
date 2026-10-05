package io.github.p_feed.feed;

import io.github.p_feed.ApiApplication;
import io.github.p_feed.infra.RedisKeys;
import io.github.p_feed.video.Video;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 8 步三级缓存四条契约：① 100 并发同 id 只查 1 次库；② L2 命中回写 L1；③ 输出按输入顺序、缺失静默；
 * ④ 全 miss 后异步回写 L2 的 TTL ≈ 1 小时。
 * FeedMapper 用 @MockitoBean 替掉，"打了几次库"才能被 verify 精确数出来；Redis 用真的。
 * 每个用例用不重复的随机 id，避免 L1（进程内、跨方法存活）与 Redis 串味。
 * 时间断言一律比 toInstant()：Jackson 3 读回来会把偏移归一化成 UTC，比 OffsetDateTime 对象必假。
 */
@SpringBootTest(classes = ApiApplication.class)
class VideoEntityCacheTest {

    private static final long PROBE_ID_BASE = 9_700_000_000L;
    private static final long PROBE_ID_RANGE = 1_000_000_000L;

    @Autowired private VideoEntityCache videoEntityCache;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisKeys keys;

    @MockitoBean private FeedMapper feedMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<Long> createdIds = new ArrayList<>();

    @BeforeEach
    void clearLocalCache() {
        videoEntityCache.invalidateAll();
    }

    @AfterEach
    void deleteCreatedKeys() {
        if (!createdIds.isEmpty()) {
            redis.delete(createdIds.stream().map(this::entityKey).toList());
        }
        videoEntityCache.invalidateAll();
    }

    @Test
    void concurrent100Misses_queryDbOnce() throws Exception {
        long id = newId();
        Video video = sampleVideo(id);
        when(feedMapper.listByIds(any())).thenAnswer(inv -> {
            Thread.sleep(100);                  // 拖住领头者，让 100 个线程都落在 singleflight 窗口内
            return List.of(video);
        });

        int n = 100;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<List<Video>>> futures = new ArrayList<>(n);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();              // 100 个线程同一瞬间起跑，否则会退化成假并发
                    return videoEntityCache.getVideoByIds(List.of(id));
                }));
            }
            start.countDown();
            for (Future<List<Video>> future : futures) {
                assertThat(future.get(20, TimeUnit.SECONDS)).hasSize(1);
            }
        }

        verify(feedMapper, times(1)).listByIds(any());
    }

    @Test
    void l2Hit_writesBackL1() {
        long id = newId();
        Video video = sampleVideo(id);
        redis.opsForValue().set(entityKey(id), objectMapper.writeValueAsString(video), Duration.ofHours(1));

        List<Video> first = videoEntityCache.getVideoByIds(List.of(id));
        assertThat(first).hasSize(1);
        assertThat(first.get(0).getCreateTime().toInstant()).isEqualTo(video.getCreateTime().toInstant());

        redis.delete(entityKey(id));            // 只剩 L1 能救这次请求
        assertThat(videoEntityCache.getVideoByIds(List.of(id))).hasSize(1);
        verify(feedMapper, never()).listByIds(any());

        videoEntityCache.invalidateAll();       // 清掉 L1 才该打库：反证上面那次是 L1 命中
        when(feedMapper.listByIds(any())).thenReturn(List.of(video));
        assertThat(videoEntityCache.getVideoByIds(List.of(id))).hasSize(1);
        verify(feedMapper, times(1)).listByIds(any());
    }

    @Test
    void inputOrderKept_missingIdDropped() throws Exception {
        long a = newId();
        long b = newId();
        long missing = newId();
        Video videoA = sampleVideo(a);
        Video videoB = sampleVideo(b);
        Map<Long, Video> rows = Map.of(a, videoA, b, videoB);
        when(feedMapper.listByIds(any())).thenAnswer(inv -> {
            List<Long> asked = inv.getArgument(0);
            return asked.stream().map(rows::get).filter(Objects::nonNull).toList();
        });

        assertThat(videoEntityCache.getVideoByIds(List.of(b, a, missing)))
                .extracting(Video::getId).containsExactly(b, a);

        videoEntityCache.invalidateAll();       // 同一批反着问，输出也跟着反：证明重排跟着输入走
        assertThat(videoEntityCache.getVideoByIds(List.of(a, b, missing)))
                .extracting(Video::getId).containsExactly(a, b);

        Thread.sleep(100);                      // 回写是异步的，等它有机会落地
        assertThat(redis.hasKey(entityKey(missing))).isFalse();   // 查不到不写缓存
    }

    @Test
    void allMiss_l2WriteBackTtlOneHour() throws Exception {
        long id = newId();
        Video video = sampleVideo(id);
        when(feedMapper.listByIds(any())).thenReturn(List.of(video));

        assertThat(videoEntityCache.getVideoByIds(List.of(id))).hasSize(1);

        String key = entityKey(id);
        String json = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            json = redis.opsForValue().get(key);
            if (json != null) {
                break;
            }
            Thread.sleep(10);                   // 回写是异步的，必须轮询等它落地
        }
        assertThat(json).as("L2 异步回写没落地: %s", key).isNotNull();

        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull();
        assertThat(ttl).isBetween(3500L, 3600L);

        Video cached = objectMapper.readValue(json, Video.class);
        assertThat(cached.getId()).isEqualTo(id);
        assertThat(cached.getCreateTime().toInstant()).isEqualTo(video.getCreateTime().toInstant());
    }

    private long newId() {
        long id = PROBE_ID_BASE + ThreadLocalRandom.current().nextLong(PROBE_ID_RANGE);
        createdIds.add(id);
        return id;
    }

    private String entityKey(long videoId) {
        return keys.key("video:entity:%d", videoId);
    }

    private Video sampleVideo(long id) {
        Video video = new Video();
        video.setId(id);
        video.setAuthorId(9_900_000_001L);
        video.setUsername("step8_probe");
        video.setTitle("step8-" + id);
        video.setDescription("entity cache probe");
        video.setPlayUrl("http://localhost:8081/static/videos/probe.mp4");
        video.setCoverUrl("http://localhost:8081/static/covers/probe.png");
        video.setCreateTime(OffsetDateTime.of(2026, 9, 25, 10, 30, 0, 0, ZoneOffset.ofHours(8)));
        video.setLikesCount(0L);
        video.setPopularity(0L);
        return video;
    }
}
