package io.github.p_feed.feed;

import io.github.p_feed.ApiApplication;
import io.github.p_feed.feed.dto.FeedVideoItem;
import io.github.p_feed.feed.dto.ListByFollowingResponse;
import io.github.p_feed.infra.RedisKeys;
import io.github.p_feed.video.Video;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 第 7 步缓存行为：① 第二次请求命中整页缓存、不再查库；② SCAN 失效只删目标账号的键。
 * 用 @MockitoBean 替掉 FeedMapper（真 Redis），这样"有没有查库"能被 verify 精确断言。
 */
@SpringBootTest(classes = ApiApplication.class)
class FollowingFeedCacheTest {

    @Autowired private FeedService feedService;
    @Autowired private FollowingFeedCache followingFeedCache;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisKeys keys;
    @MockitoBean private FeedMapper feedMapper;

    @Test
    void secondCall_hitsPageCache_andDoesNotQueryDbAgain() {
        long accountId = 999_000_123L;
        Video video = sampleVideo(777L, accountId);
        when(feedMapper.listByFollowing(eq(10), eq(accountId), isNull())).thenReturn(List.of(video));

        ListByFollowingResponse first = feedService.listByFollowing(10, null, accountId);
        ListByFollowingResponse second = feedService.listByFollowing(10, null, accountId);

        // 两次内容一致，且只有第一次查了库
        assertThat(first.videoList()).extracting(FeedVideoItem::id).containsExactly(777L);
        assertThat(second.videoList()).extracting(FeedVideoItem::id).containsExactly(777L);
        assertThat(second.nextTime()).isEqualTo(first.nextTime());
        verify(feedMapper, times(1)).listByFollowing(anyInt(), anyLong(), any());

        // 页键存在且 TTL 在 24h 附近
        String key = keys.key("feed:listByFollowing:limit=10:accountID=%d:before=0", accountId);
        Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
        assertThat(ttl).as("整页缓存 TTL ≈ 24h").isBetween(86_000L, 86_400L);

        redis.delete(key);
    }

    @Test
    void invalidate_removesOnlyTargetAccountKeys() {
        long a = 999_000_201L;
        long b = 999_000_202L;
        String keyA1 = keys.key("feed:listByFollowing:limit=10:accountID=%d:before=0", a);
        String keyA2 = keys.key("feed:listByFollowing:limit=20:accountID=%d:before=100", a);
        String keyB = keys.key("feed:listByFollowing:limit=10:accountID=%d:before=0", b);
        redis.opsForValue().set(keyA1, "{}");
        redis.opsForValue().set(keyA2, "{}");
        redis.opsForValue().set(keyB, "{}");

        followingFeedCache.invalidate(a);

        assertThat(redis.hasKey(keyA1)).as("a 的第一页键要删掉").isFalse();
        assertThat(redis.hasKey(keyA2)).as("a 的其他 limit 键也要删掉").isFalse();
        assertThat(redis.hasKey(keyB)).as("b 的键必须保留").isTrue();

        redis.delete(keyB);
    }

    private Video sampleVideo(long id, long authorId) {
        Video v = new Video();
        v.setId(id);
        v.setAuthorId(authorId);
        v.setUsername("cache_probe");
        v.setTitle("t");
        v.setDescription("d");
        v.setPlayUrl("http://localhost/static/probe/p.mp4");
        v.setCoverUrl("http://localhost/static/probe/c.jpg");
        v.setCreateTime(OffsetDateTime.now());
        v.setLikesCount(0L);
        v.setPopularity(0L);
        return v;
    }
}
