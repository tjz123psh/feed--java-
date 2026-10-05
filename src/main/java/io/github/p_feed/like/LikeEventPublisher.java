package io.github.p_feed.like;

public interface LikeEventPublisher{
    // 点赞的事件发布器：
    // true：异步投递，false：异步失败，同步投递
    boolean publishLike(long accountId, long videoId);
    boolean publishUnlike(long accountId, long videoId);
}
