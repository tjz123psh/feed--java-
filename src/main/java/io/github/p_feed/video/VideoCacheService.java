package io.github.p_feed.video;

import io.github.p_feed.infra.ApiException;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.infra.RedisKeys;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

// 视频详情读路径：缓存 + 分布式锁 + 轮询

@Component
public class VideoCacheService {
    private static final Logger log = LoggerFactory.getLogger(VideoCacheService.class);

    private static final Duration DETAIL_TTL = Duration.ofMinutes(5);
    private static final Duration LOCK_TTL = Duration.ofSeconds(2);
    private static final Duration HOT_WINDOW_TTL = Duration.ofHours(2);
    private static final long POLL_INTERVAL_MS = 20;
    private static final int POLL_ATTEMPTS = 5;

    // lua释放锁：token匹配才删除，防止锁过期被他人抢走后误删除
    private static final String UNLOCK_LUA =
            "if redis.call('get',KEYS[1]) == ARGV[1] then return redis.call('del',KEYS[1]) else"
                    + " return 0 end";

    private final StringRedisTemplate redis;
    private final RedisKeys keys;
    private final VideoMapper videoMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VideoCacheService(StringRedisTemplate redis, RedisKeys keys, VideoMapper videoMapper) {
        this.redis = redis;
        this.keys = keys;
        this.videoMapper = videoMapper;
    }

    // 详情主入口：读缓存-> miss -> 抢锁 -> double check -> 查库-> 写缓存 -> lua释放
    public Video getDetail(long videoId) {
        // 1.先读缓存
        Video cached = readCache(videoId);
        if (cached != null) {
            return cached;
        }

        // 2.抢锁：set nx px 2s , token 用于释放时的归属校验
        String token = UUID.randomUUID().toString();
        if (tryLock(videoId, token)) {

            try {

                // 3.double check
                Video again = readCache(videoId);
                if (again != null) {
                    return again;
                }

                // 4.查询数据库
                Video video = loadFromDb(videoId);
                writeCache(videoId, video);
                return video;
            } finally {
                unLock(videoId, token);
            }
        }

        // 6.没有抢到锁， 每20ms轮询最多5次
        for(int i = 0; i < POLL_ATTEMPTS;i++){
            sleepQuietly(POLL_INTERVAL_MS);
            Video polled = readCache(videoId);
            if(polled != null){
                return polled;
            }
        }

        // 7.等不到就自己查库，但不写缓存（防止迟到者覆盖持锁者刚写的版本
        return loadFromDb(videoId);
    }

    // 热度缓存：
    public void updatePopularityCache(long videoId,long delta){
        if(videoId == 0L || delta == 0L){
            return;
        }

        try {
            // 1.失败详情缓存 + 实体缓存两个键
            redis.delete(List.of(detailKey(videoId),entityKey(videoId)));
            // 2.窗口键： UTC 按分钟截断； member是videoId十进制串
            String minute = DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC).format(Instant.now()); // 这里是以分钟来计算的，在这一分钟内都是同一个键的产生
            String hotKey = keys.key("hot:video:1m:%s", minute);
            redis.opsForZSet().incrementScore(hotKey, String.valueOf(videoId), delta);
            // 3.ttl每次写都重设2小时
            redis.expire(hotKey, HOT_WINDOW_TTL);
        } catch (RuntimeException e) {
            log.warn("popularity cache update failed: {}",e.getMessage());
        }
    }

    private String entityKey(long videoId) {
        return keys.key("video:entity:%d", videoId);
    }

    private void sleepQuietly(long mills) {
        try {
            Thread.sleep(mills);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void unLock(long videoId, String token) {
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(UNLOCK_LUA, Long.class);
            redis.execute(script,List.of(lockKey(videoId)),token);
        } catch (RuntimeException e) {
            log.warn("video detail unLock failed: {}",e.getMessage());
        }
    }

    private void writeCache(long videoId, Video video) {
        try {
            redis.opsForValue()
                    .set(detailKey(videoId), objectMapper.writeValueAsString(video), DETAIL_TTL);
        } catch (RuntimeException e) {
            log.warn("video detail cache write failed: {}", e.getMessage());
        }
    }

    private Video loadFromDb(long videoId) {
        Video video = videoMapper.selectById(videoId);
        if (video == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, Errors.RECORD_NOT_FOUND);
        }

        return video;
    }

    private boolean tryLock(long videoId, String token) {
        try {
            return Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent(lockKey(videoId), token, LOCK_TTL));
        } catch (RuntimeException e) {
            log.warn("video detail lock failed: {}", e.getMessage());
            return false;
        }
    }

    private @NonNull String lockKey(long videoId) {
        return "lock:" + detailKey(videoId);
    }

    private Video readCache(long videoId) {
        try {
            String json = redis.opsForValue().get(detailKey(videoId));
            return json == null
                    ? null
                    : objectMapper.readValue(json, Video.class); // 这里是将redis中取出来的json转化为java对象
        } catch (RuntimeException e) {
            log.warn("video detail cache read failed: {}", e.getMessage());
            return null;
        }
    }

    private String detailKey(long videoId) {
        return keys.key("video:detail:id=%d", videoId);
    }
}
