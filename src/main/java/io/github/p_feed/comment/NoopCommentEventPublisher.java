package io.github.p_feed.comment;

import org.springframework.stereotype.Component;

@Component
public class NoopCommentEventPublisher implements CommentEventPublisher{

    @Override
    public boolean publishComment(String username, long videoId, long authorId, String content) {
        return false;
    }

    @Override
    public boolean deleteComment(long commentId) {
        return false;
    }


    
}
