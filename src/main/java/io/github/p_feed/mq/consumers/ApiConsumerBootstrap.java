package io.github.p_feed.mq.consumers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.apache.kafka.clients.admin.AdminClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.github.p_feed.config.AppProps;
import io.github.p_feed.feed.TimelineRedisService;
import io.github.p_feed.mq.AbstractConsumer;
import io.github.p_feed.mq.dlq.DlqProducer;
import io.github.p_feed.notification.NotificationMapper;
import io.github.p_feed.notification.SseHub;
import io.github.p_feed.video.VideoMapper;

/** API 进程的消费者装配：cg-timeline 的进度跟着 API 走（第 8 步）。；cg-notification 三兄弟第 6 步接入。 */
@Component
@Profile("!worker")
@ConditionalOnProperty(name = "feed.outbox.auto", havingValue = "true", matchIfMissing = true)
public class ApiConsumerBootstrap{

    private static final Logger log = LoggerFactory.getLogger(ApiConsumerBootstrap.class);

    private final List<AbstractConsumer> consumers = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();


    public ApiConsumerBootstrap(AppProps props, DlqProducer dlq, TimelineRedisService timelineRedisService, VideoMapper videoMapper, NotificationMapper notificationMapper, SseHub sseHub, AdminClient adminClient){
        consumers.add(new TimelineConsumer(dlq, props, timelineRedisService));

        // Kafka 或 MySQL 任一不可用 → 通知消费者整体不启用，打日志（《08》第 6 步）
        boolean ready = false;
        try {
            adminClient.listTopics().names().get(2, TimeUnit.SECONDS); // kafka探活
            videoMapper.selectById(1L); //mysql探活
            ready = true;
        } catch (Exception e) {
            log.warn("notification consumers not started (kafka or mysql unavailable): {}", e.toString());
        }

        if(ready){
            consumers.add(new NotificationConsumer(NotificationConsumer.Kind.LIKE, props, dlq, videoMapper, notificationMapper, sseHub));
            consumers.add(new NotificationConsumer(NotificationConsumer.Kind.COMMENT, props, dlq, videoMapper, notificationMapper, sseHub));
            consumers.add(new NotificationConsumer(NotificationConsumer.Kind.FOLLOW, props, dlq, videoMapper, notificationMapper, sseHub));
        }
        
    }

    @PostConstruct
    void startAll(){
        for(AbstractConsumer consumer : consumers){
            Thread thread = new Thread(consumer::start, "consumer-" + consumer.topic());
            thread.start();
            threads.add(thread);
        }
    }

    @PreDestroy
    void stopAll() throws InterruptedException {
        for(AbstractConsumer consumer : consumers){
            consumer.stop();;
        }

        for(Thread thread : threads){
            thread.join(5000);
            if(thread.isAlive()){
                log.warn("consumer thread still alive: {}", thread.getName());
            }
        }
    }
}
