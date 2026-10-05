package io.github.p_feed.feed;

import io.github.p_feed.infra.RedisKeys;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 热榜的 Redis 侧（第 10 步）：收口 hot:video:1m:{yyyyMMddHHmm}（写方是 VideoCacheService）与
 * hot:video:merge:1m:{yyyyMMddHHmm}（本类建的快照）。时间口径必须是 UTC 整分钟，与写方一致。
 */
@Component
public class HotWindowRedisService {

    private static final Logger log = LoggerFactory.getLogger(HotWindowRedisService.class);
    private static final DateTimeFormatter MINUTE =
            DateTimeFormatter.ofPattern("yyyyMMddHHmm").withZone(ZoneOffset.UTC);
    private static final int WINDOW_MINUTES = 60;
    private static final Duration SNAPSHOT_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redis;
    private final RedisKeys keys;

    public HotWindowRedisService(StringRedisTemplate redis, RedisKeys keys) {
        this.redis = redis;
        this.keys = keys;
    }

    // 快照不存在时用 ZUNIONSTORE 把最近60个窗口并入(sum) , ttl 为2分钟 ; 同一as_of 的翻页共用它
    public void ensureSnapshot(OffsetDateTime asOfMinute) {
        try {
            String snapshot = snapshotKey(asOfMinute);

            if (Boolean.TRUE.equals(redis.hasKey(snapshot))) {
                return;
            }

            List<String> windows = windowKeys(asOfMinute);

            // 这里的javaapi需要传入第一个,但是后面需要跟上集合,防止重复需要从索引1开始
            redis.opsForZSet()
                    .unionAndStore(windows.get(0), windows.subList(1, windows.size()), snapshot);

            redis.expire(snapshot, SNAPSHOT_TTL);
        } catch (RuntimeException e) {
            log.warn("build hot snapshot failed: asOf={},err={}", asOfMinute, e.toString());
        }
    }

    // 快照按分数倒序取 offset ... offset + limit - 1 , 解释成视频id; 解释失败或 <= 0 静默跳过
    public List<Long> idsByOffset(OffsetDateTime asOfMinute, int offset, int limit) {
        // 这里set的实现时linkedHashSet,底层保持严格有序
        Set<String> members =
                redis.opsForZSet()
                        .reverseRange(snapshotKey(asOfMinute), offset, (long) offset + limit - 1);

        if (members == null) {
            return List.of();
        }

        List<Long> ids = new ArrayList<>(members.size());
        for (String member : members) {
            try {
                long id = Long.parseLong(member);
                if (id > 0) {
                    ids.add(id);
                }
            } catch (NumberFormatException ignore) {
                // 坏member静默跳过
            }
        }
        return ids;
    }

    private List<String> windowKeys(OffsetDateTime asOfMinute) {
        List<String> windows = new ArrayList<>(WINDOW_MINUTES);
        for (int i = 0; i < WINDOW_MINUTES; i++) {
            windows.add(keys.key("hot:video:1m:%s", MINUTE.format(asOfMinute.minusMinutes(i))));
        }

        return windows;
    }

    private String snapshotKey(OffsetDateTime asOfMinute) {
        return keys.key("hot:video:merge:1m:%s", MINUTE.format(asOfMinute));
    }
}
