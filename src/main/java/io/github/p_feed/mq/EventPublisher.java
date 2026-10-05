package io.github.p_feed.mq;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.github.p_feed.infra.FeatureFlags;
import io.github.p_feed.mq.event.CommentEvent;
import io.github.p_feed.mq.event.LikeEvent;
import io.github.p_feed.mq.event.PopularityEvent;
import io.github.p_feed.mq.event.SocialEvent;
import io.github.p_feed.mq.event.TimelineEvent;
import tools.jackson.databind.ObjectMapper;

@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaProducer<String, String> producer;
    private final FeatureFlags flags;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    public EventPublisher(KafkaProducer<String, String> producer, FeatureFlags flags) {
        this.producer = producer;
        this.flags = flags;
    }

    // ===== 五个发布方法：key 决定分区；字段名就是消费者契约 =====
    public boolean publishLike(long userId, long videoId, String action) {
        return publish("like-events", String.valueOf(videoId), new LikeEvent(newEventId(), action, userId, videoId, nowIso()));
    }

    // key=videoId; delete 场景缺videoId时退化为commentId
    public boolean publishComment(Long commentId, String action, String username, Long videoId, Long authorId, String content){
        String key = String.valueOf(videoId != null ? videoId : commentId);
        return publish("comment-events", key, new CommentEvent(newEventId(), action, commentId, username, videoId, authorId, content, nowIso()));
    }

    public boolean publishSocial(long followerId, long vloggerId, String action){
        return publish("social-events", String.valueOf(followerId), new SocialEvent(newEventId(), action, followerId, vloggerId, nowIso()));
    }

    public boolean publishPopularity(long videoId, long change){
        return publish("video-popularity", String.valueOf(videoId), new PopularityEvent(newEventId(), videoId, change, nowIso()));
    }

    public boolean publishTimeline(long videoId, long createTimeMillis){
        return publish("video-timeline", String.valueOf(videoId), new TimelineEvent(newEventId(), videoId, createTimeMillis, nowIso()));
    }

    // 一次投递最多 2 秒：send 的阻塞靠 max.block.ms，等确认只等"剩余时间"
    private boolean publish(String topic, String key, Object payload) {
        if (!flags.mqEnabled()) {
            return false; // 启动探测已判定 broker 不可用，直接走同步降级
        }

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        try {
            String json = mapper.writeValueAsString(payload);
            producer.send(new ProducerRecord<>(topic, key, json))
                    .get(Math.max(1, remainingMillis(deadline)), TimeUnit.MILLISECONDS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // 还原中断标志：线程池/虚拟线程的取消信号不能被吞掉
            return false;
        } catch (Exception e) {
            // send 抛的 KafkaException/TimeoutException、get 超时、序列化失败，一律降级
            log.warn("mq publish failed topic={} key={}: {}", topic, key, e.toString());
            return false;
        }
    }

    private static long remainingMillis(long deadlineNanos) {
        return (deadlineNanos - System.nanoTime()) / 1_000_000;
    }

    private String newEventId() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String nowIso() {
        return OffsetDateTime.now().toString();
    }

}
