package io.github.p_feed.feed;

import io.github.p_feed.feed.dto.ListByFollowingResponse;
import io.github.p_feed.infra.RedisKeys;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 关注流整页响应缓存（第 7 步）。 键：v1:feed:listByFollowing:limit={n}:accountID={id}:before={秒}，TTL 24h，value =
 * 整页响应 JSON。 锁：lock:{键}，TTL 500ms（详情锁是 2s，别混）；miss → 抢锁 → double check → 查库写缓存； 没抢到 → 5×20ms 轮询；等不到
 * → 直查库不写缓存。 失效：SCAN 模式删除（禁 KEYS），失效的是"关注者自己"的页缓存。
 */
@Component
public class FollowingFeedCache {

    private static final Logger log = LoggerFactory.getLogger(FollowingFeedCache.class);
    private static final Duration PAGE_TTL = Duration.ofHours(24);
    private static final Duration LOCK_TTL = Duration.ofMillis(500);
    private static final long POLL_INTERVAL_MS = 20;
    private static final int POLL_ATTEMPTS = 5;
    private static final String UNLOCK_LUA =
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else"
                    + " return 0 end";

    private final StringRedisTemplate redis;
    private final RedisKeys keys;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public FollowingFeedCache(StringRedisTemplate redis, RedisKeys keys) {
        this.redis = redis;
        this.keys = keys;
    }

    // 读缓存 -> 抢锁 -> double check -> 查库写缓存 ; 没抢到轮询5*20ms，等不到直查库不写缓存
    public ListByFollowingResponse getOrLoad(
            long accountId, int limit, long before, Supplier<ListByFollowingResponse> loader) {

        String key = pageKey(accountId, limit, before);
        ListByFollowingResponse cached = read(key);

        if (cached != null) {
            return cached;
        }

        String token = UUID.randomUUID().toString();
        if (tryLock(key, token)) {
            try {
                ListByFollowingResponse again = read(key);

                if (again != null) {
                    return again;
                }
                ListByFollowingResponse loaded = loader.get();
                write(key, loaded);
                return loaded;
            } finally {
                unlock(key, token);
            }
        }

        for (int i = 0; i < POLL_ATTEMPTS; i++) {
            sleepQuietly(POLL_INTERVAL_MS);
            ListByFollowingResponse polled = read(key);
            if (polled != null) {
                return polled;
            }
        }

        return loader.get(); // 上述等不到直查库，不写缓存;
    }

    // 关注/取关后失效该账号的全部缓存页：SCAN迭代 + 批量DEL（禁用KEYS）
    public void invalidate(long accountId) {
        String pattern = keys.key("feed:listByFollowing:*:accountID=%d:*", accountId);

        try (Cursor<String> cursor =
                redis.scan(ScanOptions.scanOptions().match(pattern).count(200).build())) {
            List<String> batch = new ArrayList<>();
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= 100) {
                    redis.delete(batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                redis.delete(batch);
            }
        } catch (RuntimeException e) {
            log.warn(
                    "invalidate following feed cache failed: accountID={}, err={}",
                    accountId,
                    e.getMessage());
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 这里是让线程如果被打断能够安静推出，不用继续往下面执行
        }
    }

    private void unlock(String key, String token) {
        try {
            redis.execute(
                    new DefaultRedisScript<>(UNLOCK_LUA, Long.class),
                    List.of("lock:" + key),
                    token);
        } catch (Exception e) {
            log.warn(("following feed unlock failed: {}"), e.getMessage());
        }
    }

    private void write(String key, ListByFollowingResponse loaded) {
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(loaded), PAGE_TTL);
        } catch (Exception e) {
            log.warn("following feed cache write failed: {}", e.getMessage());
        }
    }

    private boolean tryLock(String key, String token) {
        try {
            return Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent("lock:" + key, token, LOCK_TTL));
        } catch (RuntimeException e) {
            log.warn("following feed lock failed: {}", e.getMessage());
            return false;
        }
    }

    private ListByFollowingResponse read(String key) {
        try {
            String json = redis.opsForValue().get(key);
            return json == null
                    ? null
                    : objectMapper.readValue(json, ListByFollowingResponse.class);
        } catch (Exception e) {
            log.warn(("following feed cache read failed: {}"), e.getMessage());
            return null; // 失败读取，缓存未命中，最后会直查库
        }
    }

    private String pageKey(long accountId, int limit, long before) {
        return keys.key(
                "feed:listByFollowing:limit=%d:accountID=%d:before=%d", limit, accountId, before);
    }
}
