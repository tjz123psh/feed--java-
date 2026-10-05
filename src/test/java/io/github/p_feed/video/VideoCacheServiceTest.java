package io.github.p_feed.video;

import io.github.p_feed.ApiApplication;
import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.infra.RedisKeys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import tools.jackson.databind.ObjectMapper;   // 测试里也用 Jackson 3，和主代码对称

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 验收：① 并发 20 次只有 1 次打库；② 缓存命中 0 次打库；③ 查无 404。
 * 用真 Redis（setIfAbsent 原子性真实存在），只 mock VideoMapper。
 * @MockitoBean 是 Boot 4 中 @MockBean 的替代（包名 org.springframework.test.context.bean.override.mockito）。
 */
@SpringBootTest(classes = ApiApplication.class)
class VideoCacheServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private VideoCacheService videoCacheService;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisKeys keys;
    @MockitoBean private VideoMapper videoMapper;

    @BeforeEach
    void cleanCacheKeys() {
        for (long id : new long[]{1, 7, 999}) {
            redis.delete(List.of(detailKey(id), lockKey(id)));
        }
    }

    @Test
    void cacheHit_doesNotTouchDb() throws Exception {
        Video v = sampleVideo(1);
        redis.opsForValue().set(detailKey(1), MAPPER.writeValueAsString(v), Duration.ofMinutes(5));

        assertThat(videoCacheService.getDetail(1).getId()).isEqualTo(1);
        verify(videoMapper, never()).selectById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void concurrent20Requests_onlyOneDbQuery() throws Exception {
        Video v = sampleVideo(7);
        // selectById 故意慢 10ms：让持锁者先写完缓存，其余线程在 100ms 轮询窗口内能读到
        when(videoMapper.selectById(7L)).thenAnswer(inv -> { Thread.sleep(10); return v; });

        int n = 20;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        List<Future<Long>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try { return videoCacheService.getDetail(7).getId(); }
                finally { done.countDown(); }
            }));
        }

        start.countDown();   // 20 个线程同时开跑
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        for (Future<Long> f : results) {
            assertThat(f.get()).isEqualTo(7L);
        }

        verify(videoMapper, times(1)).selectById(7L);   // 只有 1 次打库
        pool.shutdownNow();
    }

    @Test
    void missingVideo_returns404() {
        when(videoMapper.selectById(999L)).thenReturn(null);
        assertThatThrownBy(() -> videoCacheService.getDetail(999L))
                .isInstanceOf(ApiException.class)
                .hasMessage(Errors.RECORD_NOT_FOUND);
        verify(videoMapper, times(1)).selectById(999L);
    }

    private Video sampleVideo(long id) {
        Video v = new Video();
        v.setId(id); v.setAuthorId(100L); v.setUsername("tester");
        v.setTitle("测试视频 " + id);
        v.setPlayUrl("http://localhost:8081/static/videos/x.mp4");
        v.setCoverUrl("http://localhost:8081/static/covers/x.jpg");
        v.setLikesCount(0L); v.setPopularity(0L);
        return v;
    }

    private String detailKey(long id) { return keys.key("video:detail:id=%d", id); }
    private String lockKey(long id) { return "lock:" + detailKey(id); }
}
