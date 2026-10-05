package io.github.p_feed.comment;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import io.github.p_feed.mq.EventPublisher;

@Component
@Primary
public class KafkaCommentEventPublisher implements CommentEventPublisher{

    private final EventPublisher publisher;

    public KafkaCommentEventPublisher(EventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public boolean deleteComment(long commentId) {
        return publisher.publishComment(commentId, "delete", null, null, null, null);
    }

    @Override
    public boolean publishComment(String username, long videoId, long authorId, String content) {
        return publisher.publishComment(null, "publish", username, videoId, authorId, content);
    }

    
}
