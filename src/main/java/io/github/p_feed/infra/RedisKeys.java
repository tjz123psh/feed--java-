package io.github.p_feed.infra;

import org.springframework.stereotype.Component;

import io.github.p_feed.config.AppProps;

@Component
public class RedisKeys {
    private final String prefix;
    
    public RedisKeys(AppProps p){
        this.prefix = p.redis().keyPrefix();
    }

    public String key(String fmt, Object...args){
        return prefix + String.format(fmt,args);
    }
}
