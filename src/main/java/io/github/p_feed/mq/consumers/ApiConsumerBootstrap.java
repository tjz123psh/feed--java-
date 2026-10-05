package io.github.p_feed.mq.consumers;

import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.github.p_feed.config.AppProps;
import io.github.p_feed.feed.TimelineRedisService;
import io.github.p_feed.mq.AbstractConsumer;
import io.github.p_feed.mq.dlq.DlqProducer;

/** API 进程的消费者装配：cg-timeline 的进度跟着 API 走（第 8 步）。 */
@Component
@Profile("!worker")
@ConditionalOnProperty(name = "feed.outbox.auto", havingValue = "true", matchIfMissing = true)
public class ApiConsumerBootstrap{

    private static final Logger log = LoggerFactory.getLogger(ApiConsumerBootstrap.class);

    private final List<AbstractConsumer> consumers = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();


    public ApiConsumerBootstrap(AppProps props, DlqProducer dlq, TimelineRedisService timelineRedisService){
        consumers.add(new TimelineConsumer(dlq, props, timelineRedisService));
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
