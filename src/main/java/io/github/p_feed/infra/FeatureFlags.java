package io.github.p_feed.infra;

import org.slf4j.Logger;

import jakarta.annotation.PostConstruct;

import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class FeatureFlags {
    private static final Logger log = LoggerFactory.getLogger(FeatureFlags.class);
    private volatile boolean cacheEnabled = true;
    private volatile boolean mqEnabled = true;
    // 这里的volatile字段是多线程情况下的这两个服务挂了，此时可在内存级别中知道
    
    private final StringRedisTemplate redis;
    public FeatureFlags(StringRedisTemplate redis){
        this.redis = redis;
    }

    @PostConstruct
    void probe(){
        try {
            redis.opsForValue().get("feed:probe");
        } catch (Exception e) {
            cacheEnabled = false;
            log.warn("cache disabled: {}",e.getMessage());
        }
    }

    public boolean cacheEnabled(){
        return cacheEnabled;
    }

    public boolean mqEnabled(){
        return mqEnabled;
    }

    public void disableMq(){
        mqEnabled = false;
    }
    
}

