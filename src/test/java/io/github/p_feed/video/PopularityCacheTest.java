package io.github.p_feed.video;

import io.github.p_feed.ApiApplication;
import io.github.p_feed.infra.RedisKeys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 热度三件套的效果验证：DEL 两键 + 分钟窗口 ZINCRBY + EXPIRE 2h。
 * 连真 Redis——这套逻辑本身就是在操作 Redis，mock 掉就没有可断言的东西了。
 */
@SpringBootTest(classes = ApiApplication.class)
class PopularityCacheTest {

    private static final long VIDEO_ID = 888_001L;

    @Autowired private VideoCacheService videoCacheService;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisKeys keys;

    @Test
    void update_deletesDetailAndEntity_andIncrementsWindow() {
        String detailKey = keys.key("video:detail:id=%d", VIDEO_ID);
        String entityKey = keys.key("video:entity:%d", VIDEO_ID);
        String hotKey = keys.key("hot:video:1m:%s", currentMinuteUtc());

        // 人为塞两个键，模拟"缓存已存在"
        redis.opsForValue().set(detailKey, "{}", Duration.ofMinutes(5));
        redis.opsForValue().set(entityKey, "{}", Duration.ofMinutes(5));
        redis.delete(hotKey);
        assertThat(redis.hasKey(detailKey)).isTrue();

        videoCacheService.updatePopularityCache(VIDEO_ID, 1L);

        // 两键应被 DEL
        assertThat(redis.hasKey(detailKey)).isFalse();
        assertThat(redis.hasKey(entityKey)).isFalse();
        // 分钟窗口：member 是 videoId 十进制串，分数 = delta
        assertThat(redis.opsForZSet().score(hotKey, String.valueOf(VIDEO_ID))).isEqualTo(1.0);
        // TTL ≈ 2h（每次写都重设）
        assertThat(redis.getExpire(hotKey, TimeUnit.SECONDS)).isBetween(7000L, 7200L);

        // 负值方向（unlike 传 -1）
        videoCacheService.updatePopularityCache(VIDEO_ID, -1L);
        assertThat(redis.opsForZSet().score(hotKey, String.valueOf(VIDEO_ID))).isEqualTo(0.0);

        redis.delete(List.of(hotKey));
    }

    @Test
    void zeroGuards_doNothing() {
        String hotKey = keys.key("hot:video:1m:%s", currentMinuteUtc());
        redis.delete(hotKey);

        videoCacheService.updatePopularityCache(0L, 1L);      // videoId == 0
        videoCacheService.updatePopularityCache(VIDEO_ID, 0L); // delta == 0

        assertThat(redis.hasKey(hotKey)).isFalse();
    }

    private String currentMinuteUtc() {
        return DateTimeFormatter.ofPattern("yyyyMMddHHmm")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }
}
