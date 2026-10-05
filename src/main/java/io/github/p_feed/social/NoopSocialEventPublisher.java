package io.github.p_feed.social;

import org.springframework.stereotype.Component;

@Component
public class NoopSocialEventPublisher implements SocialEventPublisher{

    @Override
    public boolean publishFollow(long followerId, long vloggerId) {
        return false;
    }

    @Override
    public boolean publishUnFollow(long followerId, long vloggerId) {
        return false;
    }

    
}
