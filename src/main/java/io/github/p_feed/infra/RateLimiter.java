package io.github.p_feed.infra;

/** 限流器统一入口：@return true=放行（含 Redis 不可用的 fail-open）; false=超限（由拦截器写 429）。 */
public interface RateLimiter{
    boolean tryAcquire(String subject);
}
