package io.github.p_feed.mq.consumers;

import io.github.p_feed.config.AppProps;
import io.github.p_feed.mq.AbstractConsumer;
import io.github.p_feed.mq.dlq.DlqProducer;
import io.github.p_feed.mq.event.PopularityEvent;
import io.github.p_feed.video.VideoCacheService;
import tools.jackson.databind.ObjectMapper;

/** video-popularity → cg-video-write：热度缓存三件套（DEL 两键 + ZINCRBY 分钟窗口 + 重设 TTL）。 */
public class PopularityConsumer extends AbstractConsumer{

    private final VideoCacheService videoCacheService;
    private final ObjectMapper mapper = new ObjectMapper();
    

    public PopularityConsumer(AppProps props, DlqProducer dlq, VideoCacheService videoCacheService){
        super("video-popularity", "cg-video-write", props.kafka().bootstrap(), dlq);
        this.videoCacheService = videoCacheService;
    }

    @Override
    protected void handle(String key, String value) throws Exception {
        PopularityEvent evt = mapper.readValue(value, PopularityEvent.class);

        if(evt.videoId() <= 0 || evt.change() == 0){
            return;
        }

        videoCacheService.updatePopularityCache(evt.videoId(), evt.change());
    }
}
