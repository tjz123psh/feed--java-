package io.github.p_feed.like;

import org.springframework.stereotype.Component;

@Component
public class NoopLikeEventPublisher implements LikeEventPublisher{

    @Override
    public boolean publishLike(long accountId, long videoId) {
        return false;
    }

    @Override
    public boolean publishUnlike(long accountId, long videoId) {
        return false;
    }
    
}
