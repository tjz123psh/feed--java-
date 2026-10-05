package io.github.p_feed.social;


import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import io.github.p_feed.mq.EventPublisher;

@Component
@Primary
public class KafkaSocialEventPublisher implements SocialEventPublisher{

    private final EventPublisher publisher;

    public KafkaSocialEventPublisher(EventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public boolean publishFollow(long followerId, long vloggerId) {
        return publisher.publishSocial(followerId, vloggerId, "follow");
    }

    @Override
    public boolean publishUnFollow(long followerId, long vloggerId) {
        return publisher.publishSocial(followerId, vloggerId, "unfollow");
    }

    
}
