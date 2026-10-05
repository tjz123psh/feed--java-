package io.github.p_feed.mq.consumers;

import io.github.p_feed.config.AppProps;
import io.github.p_feed.feed.TimelineRedisService;
import io.github.p_feed.mq.AbstractConsumer;
import io.github.p_feed.mq.dlq.DlqProducer;
import io.github.p_feed.mq.event.TimelineEvent;
import tools.jackson.databind.ObjectMapper;

// ** video-timeline → cg-timeline（API 进程）：新视频追加进全局时间线并裁剪到最新 1000
public class TimelineConsumer extends AbstractConsumer {

    private final TimelineRedisService timelineRedisService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TimelineConsumer(DlqProducer dlq, AppProps props, TimelineRedisService timelineRedisService) {
        super("video-timeline", "cg-timeline", props.kafka().bootstrap(), dlq);
        this.timelineRedisService = timelineRedisService;
    }

    @Override
    protected void handle(String key, String value) throws Exception {
        TimelineEvent event = objectMapper.readValue(value, TimelineEvent.class);

        if(event.videoId() <= 0 || event.createTime() <= 0){
            return;
        }

        timelineRedisService.addGlobal(event.videoId(), event.createTime());
        
    }

    
}
