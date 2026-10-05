package io.github.p_feed.mq.consumers;

import java.util.ArrayList;
import java.util.List;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import io.github.p_feed.comment.CommentMapper;
import io.github.p_feed.config.AppProps;
import io.github.p_feed.like.LikeMapper;
import io.github.p_feed.mq.AbstractConsumer;
import io.github.p_feed.mq.dlq.DlqProducer;
import io.github.p_feed.social.SocialMapper;
import io.github.p_feed.video.VideoCacheService;
import io.github.p_feed.video.VideoMapper;

/** Worker 进程装配：每个 topic 一个线程；退出信号 → stop()（wakeup）→ 循环收尾 → close()。 */
@Component
@Profile("worker")

public class ConsumerBootstrap{

    private static final Logger log = LoggerFactory.getLogger(ConsumerBootstrap.class);
    
    private final List<AbstractConsumer> consumers = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();

    public ConsumerBootstrap(AppProps props, DlqProducer dlq, LikeMapper likeMapper, VideoMapper videoMapper, CommentMapper commentMapper, SocialMapper socialMapper, VideoCacheService videoCacheService){
        consumers.add(new LikeConsumer(props, dlq, likeMapper, videoMapper));
        consumers.add(new CommentConsumer(props, dlq, commentMapper, videoMapper));
        consumers.add(new SocialConsumer(props, dlq, socialMapper));
        consumers.add(new PopularityConsumer(props, dlq, videoCacheService));
    }

    @PostConstruct
    void startAll(){
        for(AbstractConsumer consumer : consumers){
            Thread thread = new Thread(consumer::start, "consumer-" + consumer.topic());
            thread.start();
            threads.add(thread);
        }
        log.info("worker consumers started: {}", consumers.size());
    }

    @PreDestroy
    void stopAll() throws InterruptedException {
        for(AbstractConsumer consumer : consumers){
            consumer.stop();
        }

        // 呼叫停止,但是线程如果正在写,需要接下来的join进行等待一些时间,基本上0.几秒即可结束
        for(Thread thread : threads){
            thread.join(5000);
            
            // 如果线程卡死,并没有结束线程,此时这里为true,打印错误信息,排查错误
            if(thread.isAlive()){
                log.warn("consumers thread still alive");
            }
        }
        log.info("worker consumers stopped");
    }
}
