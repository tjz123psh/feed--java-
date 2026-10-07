package io.github.p_feed.mq.consumers;

import java.time.OffsetDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.p_feed.config.AppProps;
import io.github.p_feed.mq.AbstractConsumer;
import io.github.p_feed.mq.dlq.DlqProducer;
import io.github.p_feed.mq.event.CommentEvent;
import io.github.p_feed.mq.event.LikeEvent;
import io.github.p_feed.mq.event.SocialEvent;
import io.github.p_feed.notification.Notification;
import io.github.p_feed.notification.NotificationMapper;
import io.github.p_feed.notification.SseHub;
import io.github.p_feed.video.Video;
import io.github.p_feed.video.VideoMapper;
import tools.jackson.databind.ObjectMapper;

/**
 * like/comment/social 三 topic → cg-notification（API 进程内）：
 * 过滤 action → 查视频作者（follow 不查库）→ 操作者=作者跳过 → INSERT notifications → Hub.push。
 * 落库与推送分离：推送失败不影响 commit；DB 写失败走《07》的重试 3 次 → <topic>.dlq。
 */

public class  NotificationConsumer extends  AbstractConsumer{

    private static final Logger log = LoggerFactory.getLogger(NotificationConsumer.class);

    // 三个实例: 同一条逻辑, 只有topic/ 放行的action不同
    public enum Kind {
        LIKE("like-events", "like"),
        COMMENT("comment-events", "publish"),
        FOLLOW("social-events" , "follow");

        final String topic;
        final String action;

        Kind(String topic, String action){
            this.topic = topic;
            this.action = action;
        }
    }

    private final Kind kind;
    private final VideoMapper videoMapper;
    private final NotificationMapper notificationMapper;
    private final SseHub sseHub;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public NotificationConsumer(Kind kind, AppProps props, DlqProducer dlq, VideoMapper videoMapper, NotificationMapper notificationMapper, SseHub sseHub){
        super(kind.topic, "cg-notification", props.kafka().bootstrap(), dlq);
        this.kind = kind;
        this.notificationMapper = notificationMapper;
        this.sseHub = sseHub;
        this.videoMapper = videoMapper;
    }


    @Override
    protected void handle(String key, String value) throws Exception {
        switch(kind){
            case LIKE -> handleLike(value);
            case COMMENT -> handleComment(value);
            case FOLLOW -> handleFollow(value);
        }
    }



    private void handleLike(String value) throws Exception{
        LikeEvent event = objectMapper.readValue(value, LikeEvent.class);
        if(!kind.action.equals(event.action())){
            return; // unlike不产生通知
        }

        if(event.userId() <= 0 || event.videoId() <= 0){
            return;
        }

        Video video = videoMapper.selectById(event.videoId());
        if(video == null || video.getAuthorId() == null || video.getAuthorId() == 0){
            return; // 已删 / 作者缺失: 业务性差异
        }

        long authorId = video.getAuthorId();
        if(authorId == event.userId()){
            return ; // 自己赞自己
        }

        notify(authorId, event.userId(), "like", event.videoId(), "点赞了你的视频");
    }

    private void handleComment(String value) {
        CommentEvent event = objectMapper.readValue(value, CommentEvent.class);
        if(!kind.action.equals(event.action())){
            //删除不产生通知
            return;
        }

        if(event.authorId() == null || event.videoId() == null || event.authorId() == 0 || event.videoId() == 0){
            return;
        }

        Video video = videoMapper.selectById(event.videoId());
        if(video == null || video.getAuthorId() == null || video.getAuthorId() == 0){
            return;
        }

        long authorId = video.getAuthorId();
        if(authorId == event.authorId()){
            return;
        }

        notify(authorId, event.authorId(), "comment", event.videoId(), "评论了你的视频");
    }

    private void handleFollow(String value) {
        SocialEvent event = objectMapper.readValue(value, SocialEvent.class);
        if(!kind.action.equals(event.action())){
            return; 
        }

        if(event.followerId() <= 0 || event.vloggerId() <= 0){
            return;
        }

        if(event.followerId() == event.vloggerId()){
            return;
        }

        // 这里的follow的target_id是粉丝自己,不是被关注的人; target_id是给前端进行跳转到物件通道指引
        notify(event.vloggerId(), event.followerId(), "follow", event.followerId(), "关注了你");
    }


    private void notify(long authorId, long userId, String type, long targetId, String content) {
        Notification n = new Notification();
        n.setRecipientId(authorId);
        n.setSenderId(userId);
        n.setType(type);
        n.setTargetId(targetId);
        n.setContent(content);
        n.setIsRead(false);
        n.setCreatedAt(OffsetDateTime.now());
        notificationMapper.insert(n);

        try {
            sseHub.push(authorId, n); // 推送失败不影响
        } catch (Exception e) {
            log.warn("sse push failed: recipient={} err={}", authorId, e.toString());
        }
    }
}
