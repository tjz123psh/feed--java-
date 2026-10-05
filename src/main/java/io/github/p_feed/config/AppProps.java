package io.github.p_feed.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "feed")
public record AppProps(Redis redis,Jwt jwt,String uploadRoot, Kafka kafka, Outbox outbox){
    public record Redis(String keyPrefix) {
    }

    public record Jwt(String secret){
    }

    public record Kafka(String bootstrap){
    }

    public record Outbox(long fixedDelayMs, int batchSize) {
    }
}



