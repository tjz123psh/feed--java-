package io.github.p_feed.mq.consumers;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import org.springframework.dao.DuplicateKeyException;

import io.github.p_feed.config.AppProps;
import io.github.p_feed.mq.AbstractConsumer;
import io.github.p_feed.mq.dlq.DlqProducer;
import io.github.p_feed.mq.event.SocialEvent;
import io.github.p_feed.social.Social;
import io.github.p_feed.social.SocialMapper;
import tools.jackson.databind.ObjectMapper;

/** social-events → cg-video-write：follow 插行（唯一索引冲突吞掉，幂等）；unfollow 删行。 */
public class SocialConsumer extends AbstractConsumer{

    private final SocialMapper socialMapper;
    private final ObjectMapper mapper = new ObjectMapper();

    public SocialConsumer(AppProps props, DlqProducer dlq, SocialMapper socialMapper){
        super("social-events", "cg-video-write", props.kafka().bootstrap(), dlq);
        this.socialMapper = socialMapper;
    }

    @Override
    protected void handle(String key, String value) throws Exception {
        SocialEvent evt = mapper.readValue(value, SocialEvent.class);

        if(evt.followerId() <= 0 || evt.vloggerId() <= 0){
            return;
        }

        switch(evt.action()){
            case "follow" -> applyFollow(evt);
            case "unfollow" -> applyUnfollow(evt);
            default -> {}
        }
    }

    private void applyFollow(SocialEvent evt) {
        Social social = new Social();
        social.setFollowerId(evt.followerId());
        social.setVloggerId(evt.vloggerId());
        try {
            socialMapper.insert(social);
        } catch (DuplicateKeyException e) {
            // 唯一索引idx_social_follower_vlogger 拦下重复：幂等吞掉
        }
    }

    private void applyUnfollow(SocialEvent evt) {
        socialMapper.delete(new LambdaQueryWrapper<Social>().eq(Social::getFollowerId, evt.followerId()).eq(Social::getVloggerId, evt.vloggerId()) );
    }
}
