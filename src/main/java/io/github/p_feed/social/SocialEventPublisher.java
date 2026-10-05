package io.github.p_feed.social;

public interface SocialEventPublisher{
    // 关注域的事件发布器

    boolean publishFollow(long followerId, long vloggerId);

    boolean publishUnFollow(long followerId, long vloggerId);
    
}
