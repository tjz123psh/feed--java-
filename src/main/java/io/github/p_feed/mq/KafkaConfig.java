package io.github.p_feed.mq;

import io.github.p_feed.config.AppProps;
import io.github.p_feed.infra.FeatureFlags;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Bean(destroyMethod = "close")
    public KafkaProducer<String, String> kafkaProducer(AppProps props) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.kafka().bootstrap());
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000); // send阻塞上限
        cfg.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2000); // 发出后等待确认的总上限
        cfg.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
        cfg.put(ProducerConfig.ACKS_CONFIG, "1");
        cfg.put(ProducerConfig.RETRIES_CONFIG, 3);
        cfg.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, false); // 关闭生产者幂等性,防止acks=all

        return new KafkaProducer<>(cfg);
    }

    // 管理客户端只服务启动探测；超时必须收紧，否则探测会把启动拖满 60 秒
    @Bean(destroyMethod = "close")
    public AdminClient adminClient(AppProps props) {
        return AdminClient.create(
                Map.of(
                        AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
                        props.kafka().bootstrap(),
                        AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG,
                        2000,
                        AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG,
                        1000));
    }

    // 上面和下面构成内外双保险,上管的是kafka,下管的是spring主线程

    // 启动探测：连不上 → 一行 warn + mqEnabled=false，此后所有投递直接返回 false（全走同步）
    @Bean
    public ApplicationRunner mqStartupProbe(AdminClient admin, FeatureFlags flags) {
        return args -> {
            try {
                admin.listTopics().names().get(2, TimeUnit.SECONDS);
                log.info("mq enabled");
            } catch (Exception e) {
                flags.disableMq();
                log.warn("mq disabled: {}", e.getMessage());
            }
        };
    }
}
