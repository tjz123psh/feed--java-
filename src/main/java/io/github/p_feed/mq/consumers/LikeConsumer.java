package io.github.p_feed.mq.consumers;

import io.github.p_feed.config.AppProps;
import io.github.p_feed.like.LikeMapper;
import io.github.p_feed.mq.AbstractConsumer;
import io.github.p_feed.mq.dlq.DlqProducer;
import io.github.p_feed.mq.event.LikeEvent;
import io.github.p_feed.video.VideoMapper;
import tools.jackson.databind.ObjectMapper;

/** like-events → cg-video-write：落 likes 行 + likes_count/popularity 列（幂等靠唯一索引）。 */
public class LikeConsumer extends AbstractConsumer{

    private final LikeMapper likeMapper;
    private final VideoMapper videoMapper;
    private final ObjectMapper mapper = new ObjectMapper();

    public LikeConsumer(AppProps props, DlqProducer dlq, LikeMapper likeMapper, VideoMapper videoMapper){
        super("like-events", "cg-video-write", props.kafka().bootstrap(), dlq);
        this.likeMapper = likeMapper;
        this.videoMapper = videoMapper;
    }

    @Override
    protected void handle(String key, String value) throws Exception {
        LikeEvent event = mapper.readValue(value, LikeEvent.class); //解析失败直接进入dlq
        
        if(event.userId() <= 0 || event.videoId() <= 0){
            return;// 非法值,业务性丢弃; 提交跳过
        }
        if(videoMapper.selectById(event.videoId()) == null){
            return; // 发布后被删除的竞态: 业务性丢弃,不进dlq
        }

        switch(event.action()){
            case "like" -> applyLike(event);
            case "unlike" -> applyUnlike(event);
            default -> {}
        }
    }

    private void applyUnlike(LikeEvent event) {
        if(likeMapper.deleteLike(event.videoId(), event.userId()) == 0){
            return; // 没赞过: 幂等跳过
        }

        videoMapper.decrementLikesCount(event.videoId());
        videoMapper.addPopularityClamped(event.videoId(), -1L);
    }

    private void applyLike(LikeEvent event) {
        if(!likeMapper.insertIgnore(event.videoId(), event.userId())){
            return; // 已赞过,幂等跳过
        }

        videoMapper.incrementLikesCount(event.videoId());
        videoMapper.addPopularity(event.videoId(), 1L);
    }
}
