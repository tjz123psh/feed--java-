package io.github.p_feed.like;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import io.github.p_feed.mq.EventPublisher;

/** 阶段 7：投 like-events；返回 false 时由 LikeService 走同步降级。 */
@Component
@Primary
public class KafkaLikeEventPublisher implements LikeEventPublisher{

    private final EventPublisher publisher;

    public KafkaLikeEventPublisher(EventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public boolean publishLike(long accountId, long videoId) {
        return publisher.publishLike(accountId, videoId, "like");
        
    }

    @Override
    public boolean publishUnlike(long accountId, long videoId) {
        return publisher.publishLike(accountId, videoId, "unlike");
    }
    
}
