package io.github.p_feed.infra;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 五条规则 = 五个 Bean；@Bean 方法名就是规则名（拦截器按名字取）。 */
@Configuration 
@ConditionalOnProperty(name = "feed.ratelimit.enabled", havingValue = "true", matchIfMissing = true)
public class  RateLimitConfig{

    @Bean 
    public RateLimiter account_register(StringRedisTemplate redis){
        return new RedisRateLimiter(redis, rule("account_register"));
    }

    @Bean 
    public RateLimiter account_login(StringRedisTemplate redis){
        return new RedisRateLimiter(redis, rule("account_login"));
    }

    @Bean 
    public RateLimiter like_write(StringRedisTemplate redis){
        return new RedisRateLimiter(redis, rule("like_write"));
    }

    @Bean 
    public RateLimiter comment_write(StringRedisTemplate redis){
        return new RedisRateLimiter(redis, rule("comment_write"));
    }

    @Bean 
    public RateLimiter social_write(StringRedisTemplate redis){
        return new RedisRateLimiter(redis, rule("social_write"));
    }
    private static RateLimits.Rule rule(String name){
        return RateLimits.ALL.stream()
                .filter(r -> r.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
