package io.github.p_feed.mq;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.github.p_feed.mq.dlq.DlqProducer;
import tools.jackson.core.JacksonException;

/**
 * 本项目唯一实现消费语义的地方： ① poll → 逐条处理 → 整批处理完 commit（at-least-once，重复靠幂等兜底）； ② 单条失败：内存重试 3
 * 次（1s/2s/4s），仍失败 → 转投 <topic>.dlq → 随批提交跳过； ③ JSON 解析失败（JacksonException）：不重试，直接进 DLQ； ④ stop()：唤醒
 * poll、处理完在途消息、提交后退出。 子类只实现 handle()：正常返回=成功；抛普通异常=可重试失败；抛 JacksonException=解析失败。
 */
public abstract class AbstractConsumer {
    private static final Logger log = LoggerFactory.getLogger(AbstractConsumer.class);
    private static final int MAX_RETRIES = 3;
    private static final long[] RETRY_BACKOFF_MS = {1000, 2000, 4000};

    private final String topic;
    private final String groupId;
    private final DlqProducer dlq;
    private final KafkaConsumer<String, String> consumer;
    private final AtomicBoolean running = new AtomicBoolean(true);

    // protected在构造器的使用是专门给子类初始化用的,外部无关的代码不需要实例化
    protected AbstractConsumer(String topic, String groupId, String bootstrap, DlqProducer dlq) {
        this.topic = topic;
        this.groupId = groupId;
        this.dlq = dlq;

        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        this.consumer = new KafkaConsumer<>(cfg);
    }

    // 阻塞运行,直到stop()
    public void start() {
        consumer.subscribe(List.of(topic));
        log.info("consumer started: topic={} group={}", topic, groupId);

        try {
            while (running.get()) {
                ConsumerRecords<String, String> records;
                try {
                    records = consumer.poll(Duration.ofMillis(500));
                } catch (WakeupException e) {
                    break; // stop()预期唤醒
                }

                for (ConsumerRecord<String, String> record : records) {
                    processOne(record);
                }

                if (!records.isEmpty()) {
                    commitAfterWakeup();
                }
            }
        } finally {
            consumer.close();
            log.info("consumer closed: topic={} group={}", topic, groupId);
        }
    }

    // 线程安全: 置停止位并唤醒可能阻塞中的poll
    public void stop(){
        running.set(false);
        consumer.wakeup();
    }

    // 装配线程名用
    public String topic(){
        return topic;
    }

    // 子类唯一要实现的地方, 子类实现具体的代码逻辑来进行工作,下面是出现父类的调用
    protected abstract void handle(String key, String value) throws Exception;


    private void processOne(ConsumerRecord<String, String> record) {
        for (int attempt = 0; ; attempt++) {
            try {
                handle(record.key(), record.value()); // 解析Json字符串
                return;
            } catch (JacksonException e) {
                dlq.send(topic, record, e);
                return; // 解析失败不重试, 重试多少次JSON还是那个JSON
            } catch (Exception e) {
                if (attempt >= MAX_RETRIES) {
                    dlq.send(topic, record, e); // 首次 + 3 次重试都失败
                    return;
                }

                log.warn(
                    "retry {}/{} topic={} offset={} err={}",
                    attempt + 1,
                    MAX_RETRIES,
                    topic,
                    record.offset(),
                    e.toString());

                sleepQuietly(RETRY_BACKOFF_MS[attempt]);
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    private void commitAfterWakeup() {
        try {
            consumer.commitSync();
        } catch (WakeupException e) {
            // stop() 的唤醒撞上提交窗口：标志只会生效一次，再提交一次，
            // 保证"在途消息已提交再退出"这条语义在竞态下也成立
            consumer.commitSync();
        }
    }
}
