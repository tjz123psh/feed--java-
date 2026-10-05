package io.github.p_feed.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import io.github.p_feed.ApiApplication;
import io.github.p_feed.config.AppProps;
import io.github.p_feed.feed.TimelineRedisService;
import io.github.p_feed.infra.RedisKeys;
import io.github.p_feed.video.OutboxMsg;
import io.github.p_feed.video.OutboxMsgMapper;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 8 步 Outbox→时间线：① pending 行经 poll() 投递成功后被删除（不是改 done）；
 * ② 事件被 cg-timeline 消费后写进 v1:feed:global_timeline（score=create_time 毫秒）；
 * ③ addGlobal 后 zcard 不超 1000（只留最新）。
 * 探针用 99 亿段 videoId，跑完全部清理；本类特意把 feed.outbox.auto 打开。
 */
@SpringBootTest(classes = ApiApplication.class, properties = "feed.outbox.auto=true")
class TimelineOutboxTest {

    private static final long PROBE_VIDEO = 999000123L;

    @Autowired private OutboxMsgMapper outboxMsgMapper;
    @Autowired private OutboxPoller poller;
    @Autowired private TimelineRedisService timelineRedis;
    @Autowired private StringRedisTemplate redis;
    @Autowired private RedisKeys keys;
    @Autowired private AppProps props;

    @AfterEach
    void cleanup() {
        outboxMsgMapper.delete(new LambdaQueryWrapper<OutboxMsg>().eq(OutboxMsg::getVideoId, PROBE_VIDEO));
        redis.opsForZSet().remove(keys.key("feed:global_timeline"), String.valueOf(PROBE_VIDEO));
    }

    @Test
    void pendingRow_deliveredThenDeleted_andConsumedIntoGlobalTimeline() throws Exception {
        long createMillis = System.currentTimeMillis();
        insertPending(createMillis);

        poller.poll(); // 手动催一把；定时器同时在跑也无妨（重复投递会被 ZADD 同 member 覆盖）
        awaitOutboxGone();
        awaitGlobalScore(createMillis);

        // handle 里若抛异常：ZADD 可能已先成功、消息却要在重试窗口（1+2+4s）后进 DLQ——
        // 等窗口过去再查 DLQ，专抓这种"半成功"（本步真踩过：remove 误用成非 removeRange）
        Thread.sleep(10_000);
        assertThat(dlqHasProbe()).as("探针消息不应出现在 video-timeline.dlq").isFalse();
    }

    @Test
    void addGlobal_keepsAtMost1000() {
        long score = System.currentTimeMillis();
        timelineRedis.addGlobal(PROBE_VIDEO, score);

        String key = keys.key("feed:global_timeline");
        assertThat(redis.opsForZSet().score(key, String.valueOf(PROBE_VIDEO)))
                .isEqualTo((double) score);
        assertThat(redis.opsForZSet().zCard(key)).isLessThanOrEqualTo(1000L);
    }

    private void insertPending(long createMillis) {
        OutboxMsg row = new OutboxMsg();
        row.setVideoId(PROBE_VIDEO);
        row.setEventType("video_published");
        row.setStatus("pending");
        row.setCreateTime(OffsetDateTime.ofInstant(Instant.ofEpochMilli(createMillis), ZoneId.systemDefault()));
        outboxMsgMapper.insert(row);
    }

    private void awaitOutboxGone() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Long pending = outboxMsgMapper.selectCount(new LambdaQueryWrapper<OutboxMsg>()
                    .eq(OutboxMsg::getVideoId, PROBE_VIDEO)
                    .eq(OutboxMsg::getStatus, "pending"));
            if (pending == 0) {
                return;
            }
            sleepQuietly(200);
        }
        throw new AssertionError("5s 内 outbox 行没有被投递删除");
    }

    private void awaitGlobalScore(long expectedScore) {
        String key = keys.key("feed:global_timeline");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Double score = redis.opsForZSet().score(key, String.valueOf(PROBE_VIDEO));
            if (score != null && score.longValue() == expectedScore) {
                return;
            }
            sleepQuietly(300);
        }
        throw new AssertionError("10s 内 global_timeline 没等到探针 videoId 的 score");
    }

    private boolean dlqHasProbe() {
        try (KafkaConsumer<String, String> reader = new KafkaConsumer<>(readerConfig("it-c8-dlq-"))) {
            reader.subscribe(List.of("video-timeline.dlq"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> r : reader.poll(Duration.ofMillis(300))) {
                    if (r.value() != null && r.value().contains(String.valueOf(PROBE_VIDEO))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private Map<String, Object> readerConfig(String groupPrefix) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.kafka().bootstrap());
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, groupPrefix + UUID.randomUUID().toString().substring(0, 8));
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return cfg;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
