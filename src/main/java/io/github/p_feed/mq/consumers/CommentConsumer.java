package io.github.p_feed.mq.consumers;



import java.time.OffsetDateTime;

import io.github.p_feed.comment.Comment;
import io.github.p_feed.comment.CommentMapper;
import io.github.p_feed.config.AppProps;
import io.github.p_feed.mq.AbstractConsumer;
import io.github.p_feed.mq.dlq.DlqProducer;
import io.github.p_feed.mq.event.CommentEvent;
import io.github.p_feed.video.VideoMapper;
import tools.jackson.databind.ObjectMapper;

/** comment-events → cg-video-write：publish 落评论行 + popularity+1；delete 删行。无幂等键（已知弱点）。 */
public class CommentConsumer extends AbstractConsumer{

    private final CommentMapper commentMapper;
    private final VideoMapper videoMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CommentConsumer(AppProps props, DlqProducer dlq, CommentMapper commentMapper, VideoMapper videoMapper){
        super("comment-events", "cg-video-write", props.kafka().bootstrap(), dlq);
        this.videoMapper = videoMapper;
        this.commentMapper = commentMapper;
    }

    @Override
    protected void handle(String key, String value) throws Exception {
        CommentEvent evt = objectMapper.readValue(value, CommentEvent.class);
        switch(evt.action()){
            case "publish" -> applyPublish(evt);
            case "delete" -> applyDelete(evt);
            default -> {}
        }
    }

    private void applyPublish(CommentEvent evt) {
        if(evt.videoId() == null || evt.authorId() == null || evt.content().isBlank()){
            return;
        }

        if (videoMapper.selectById(evt.videoId()) == null){
            return;
        }

        Comment comment = new Comment();
        comment.setUsername(evt.username() == null ? "" : evt.username().trim());
        comment.setVideoId(evt.videoId());
        comment.setAuthorId(evt.authorId());
        comment.setContent(evt.content().trim());
        comment.setCreatedAt(OffsetDateTime.now());
        commentMapper.insert(comment);
        videoMapper.addPopularity(evt.videoId(), 1L);
    }
    
    private void applyDelete(CommentEvent evt) {
        if(evt.commentId() == null || evt.commentId() == 0){
            return;
        }

        if(commentMapper.selectOneById(evt.commentId()) == null){
            return;
        }

        commentMapper.deleteOneById(evt.commentId());
    }



    
}
