package io.github.p_feed.mq;

import io.github.p_feed.ApiApplication;
import io.github.p_feed.config.AppProps;
import io.github.p_feed.mq.dlq.DlqProducer;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 6 步消费骨架四条语义：① 成功 → 逐条处理 → 整批提交（崩溃重放靠幂等兜底）；
 * ② 业务失败重试 3 次（1s/2s/4s）后转投 <topic>.dlq、随批提交跳过；
 * ③ JSON 解析失败不重试、直接进 DLQ；④ stop() 唤醒空闲 poll、在途消息提交后退出。
 * 每条用例自建随机 topic + 随机组名（干净起点），断言"处理次数 + 提交位点 + DLQ 内容与 header"。
 */
@SpringBootTest(classes = ApiApplication.class)
class AbstractConsumerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private AppProps props;
    @Autowired private KafkaProducer<String, String> producer;
    @Autowired private AdminClient admin;
    @Autowired private DlqProducer dlqProducer;

    @Test
    void successPath_commitsAfterProcessing() throws Exception {
        String topic = newTopic();
        String group = newGroup();
        RecordingConsumer consumer = new RecordingConsumer(topic, group, (k, v) -> { });
        Thread thread = new Thread(consumer::start);
        thread.start();
        try {
            producer.send(new ProducerRecord<>(topic, "7", "hello-" + topic)).get(5, TimeUnit.SECONDS);
            assertThat(consumer.firstHandled.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            consumer.stop();
            thread.join(5000);
        }
        assertThat(consumer.attempts.get()).isEqualTo(1);
        assertThat(committedOffset(group, topic)).isEqualTo(1);
    }

    @Test
    void failingMessage_retriesThreeTimes_thenDlqAndSkip() throws Exception {
        String topic = newTopic();
        String group = newGroup();
        RecordingConsumer consumer =
                new RecordingConsumer(topic, group, (k, v) -> { throw new IllegalStateException("boom"); });
        Thread thread = new Thread(consumer::start);
        thread.start();
        try {
            producer.send(new ProducerRecord<>(topic, "9", "bad-" + topic)).get(5, TimeUnit.SECONDS);
            ConsumerRecord<String, String> dlqRecord = awaitRecord(topic + ".dlq", topic, 25);
            assertThat(dlqRecord.value()).isEqualTo("bad-" + topic);
            assertThat(header(dlqRecord, "original-topic")).isEqualTo(topic);
            assertThat(header(dlqRecord, "failure-reason")).contains("boom");
        } finally {
            consumer.stop();
            thread.join(5000);
        }
        assertThat(consumer.attempts.get()).isEqualTo(4); // 首次 + 3 次重试
        assertThat(committedOffset(group, topic)).isEqualTo(1); // 进 DLQ 后提交跳过，不重放
    }

    @Test
    void nonJson_directToDlqWithoutRetry() throws Exception {
        String topic = newTopic();
        String group = newGroup();
        RecordingConsumer consumer =
                new RecordingConsumer(topic, group, (k, v) -> MAPPER.readValue(v, Map.class));
        Thread thread = new Thread(consumer::start);
        thread.start();
        try {
            producer.send(new ProducerRecord<>(topic, "1", "notjson-" + topic)).get(5, TimeUnit.SECONDS);
            ConsumerRecord<String, String> dlqRecord = awaitRecord(topic + ".dlq", topic, 10);
            assertThat(dlqRecord.value()).isEqualTo("notjson-" + topic);
        } finally {
            consumer.stop();
            thread.join(5000);
        }
        assertThat(consumer.attempts.get()).isEqualTo(1); // 不重试
        assertThat(committedOffset(group, topic)).isEqualTo(1);
    }

    @Test
    void stop_whileIdle_exitsPromptly() throws Exception {
        String topic = newTopic();
        RecordingConsumer consumer = new RecordingConsumer(topic, newGroup(), (k, v) -> { });
        Thread thread = new Thread(consumer::start);
        thread.start();
        Thread.sleep(500); // 让它进到 poll 里
        consumer.stop();
        thread.join(5000);
        assertThat(thread.isAlive()).isFalse();
    }

    // ===== 工具 =====

    private String newTopic() throws Exception {
        String topic = "it-c6-" + UUID.randomUUID().toString().substring(0, 8);
        admin.createTopics(
                        List.of(new NewTopic(topic, 1, (short) 1), new NewTopic(topic + ".dlq", 1, (short) 1)))
                .all()
                .get(10, TimeUnit.SECONDS);
        return topic;
    }

    private static String newGroup() {
        return "it-c6-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private long committedOffset(String group, String topic) {
        TopicPartition tp = new TopicPartition(topic, 0);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(plainConfig(group))) {
            // kafka-clients 4.x 移除了单分区重载，只剩 committed(Set<TopicPartition>)
            OffsetAndMetadata meta = consumer.committed(Set.of(tp)).get(tp);
            return meta == null ? -1 : meta.offset();
        }
    }

    private ConsumerRecord<String, String> awaitRecord(String topic, String marker, int timeoutSeconds) {
        try (KafkaConsumer<String, String> reader = new KafkaConsumer<>(plainConfig(newGroup()))) {
            reader.subscribe(List.of(topic));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : reader.poll(Duration.ofMillis(300))) {
                    if (record.value() != null && record.value().contains(marker)) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("在 " + timeoutSeconds + "s 内没等到含 '" + marker + "' 的记录（topic=" + topic + "）");
    }

    private Map<String, Object> plainConfig(String group) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.kafka().bootstrap());
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return cfg;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** 只记录、不写库的演示消费者：handle 的行为由构造参数注入。 */
    class RecordingConsumer extends AbstractConsumer {

        final AtomicInteger attempts = new AtomicInteger();
        final CountDownLatch firstHandled = new CountDownLatch(1);
        private final BiConsumer<String, String> logic;

        RecordingConsumer(String topic, String group, BiConsumer<String, String> logic) {
            super(topic, group, props.kafka().bootstrap(), dlqProducer);
            this.logic = logic;
        }

        @Override
        protected void handle(String key, String value) throws Exception {
            attempts.incrementAndGet();
            logic.accept(key, value);
            firstHandled.countDown();
        }
    }
}
