package io.github.p_feed.comment;

// 评论域事件发布器
public interface CommentEventPublisher{

    boolean publishComment(String username, long  videoId, long authorId, String content);

    boolean deleteComment(long commentId);
}
