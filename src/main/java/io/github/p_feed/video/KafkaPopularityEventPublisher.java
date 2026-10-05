package io.github.p_feed.video;

import org.springframework.stereotype.Component;

import io.github.p_feed.mq.EventPublisher;

@Component
public class KafkaPopularityEventPublisher implements PopularityEventPublisher{

    private final EventPublisher publisher;

    public KafkaPopularityEventPublisher(EventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public boolean publishPopularity(long videoId, long change) {
        return publisher.publishPopularity(videoId, change);
    }

    
}
