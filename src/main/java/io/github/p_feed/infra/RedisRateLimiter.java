package io.github.p_feed.infra;

import io.github.p_feed.infra.RateLimits.Rule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.List;

/**
 * 固定窗口计数（INCR + 首次 PEXPIRE，Lua 原子）； key 逐字 feedsystem:ratelimit:{规则}:{对象} —— 全项目唯一不走 v1: 前缀的键；不可用一律
 * fail-open。
 */
public class RedisRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);
    private static final String LUA =
            "local count = redis.call('INCR', KEYS[1])\n"
                    + "if count == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end\n"
                    + "return count";
    private static final DefaultRedisScript<Long> SCRIPT =
            new DefaultRedisScript<>(LUA, Long.class);

    private final StringRedisTemplate redis;
    private final RateLimits.Rule rule;

    public RedisRateLimiter(StringRedisTemplate redis, Rule rule) {
        this.redis = redis;
        this.rule = rule;
    }

    @Override
    public boolean tryAcquire(String subject) {
        String key = "feedsystem:ratelimit:" + rule.name() + ":" + subject;
        try {
            Long count =
                    redis.execute(SCRIPT, List.of(key), String.valueOf(rule.window().toMillis()));
            return count == null || count <= rule.limit();
        } catch (RuntimeException e) {
            log.warn("ratelimit fail-open: key={} err={}", key, e.toString());
            return true; // 护栏,不是门票;
        }
    }
}
