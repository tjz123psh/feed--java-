package io.github.p_feed.mq;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.github.p_feed.config.AppProps;
import io.github.p_feed.video.OutboxMsg;
import io.github.p_feed.video.OutboxMsgMapper;
/**
 * Outbox 轮询器（API 进程，单实例部署——多开会把同一批 pending 重复投递）。
 * 每 1s：取 pending 按 create_time 升序 LIMIT batch → 逐条投 video-timeline → 成功后删行。
 */
@Component
@Profile("!worker")
@ConditionalOnProperty(name = "feed.outbox.auto", havingValue = "true", matchIfMissing = true)
public class OutboxPoller{

    private final OutboxMsgMapper outboxMsgMapper;
    private final EventPublisher publisher;
    private final int batchSize;
    
    public OutboxPoller(OutboxMsgMapper outboxMsgMapper, EventPublisher publisher, AppProps props) {
        this.outboxMsgMapper = outboxMsgMapper;
        this.publisher = publisher;
        this.batchSize = props.outbox().batchSize();
    }

    // 测试会直接调它（public）；定时触发默认每 1000ms（feed.outbox.fixed-delay-ms）
    @Scheduled(fixedDelayString = "${feed.outbox.fixed-delay-ms:1000}")
    public void poll(){
        List<OutboxMsg> rows = outboxMsgMapper.selectList(new LambdaQueryWrapper<OutboxMsg>()
                                                          .eq(OutboxMsg::getStatus, "pending")
                                                          .orderByAsc(OutboxMsg::getCreateTime)
                                                          .last("limit " + batchSize));

        for(OutboxMsg row : rows){
            boolean sent = publisher.publishTimeline(row.getVideoId(), row.getCreateTime().toInstant().toEpochMilli());
            if(sent){
                outboxMsgMapper.deleteById(row.getId());// 成功后删除行,发送失败就留着,下轮重试
            }
        }
    }
    
}
