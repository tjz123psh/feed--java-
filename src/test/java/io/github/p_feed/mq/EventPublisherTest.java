
package io.github.p_feed.mq;
import io.github.p_feed.ApiApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 5 步生产者契约：broker 可达时，publishLike 应在 2 秒截止内"发出并拿到确认"、返回 true。
 * 依赖真实 Kafka（与 mysql/redis 同级：跑测试要求三个依赖都在线）；每次运行会往
 * like-events 真投一条消息，接 Java 消费者前会统一清理。broker 停掉时这条会红——
 * 那是设计内的降级路径（第 9 步演练），应用降级行为不靠这条断言证明。
 */
@SpringBootTest(classes = ApiApplication.class)
class EventPublisherTest {

    @Autowired
    private EventPublisher publisher;

    @Test
    void publishLikeReachable() {
        assertThat(publisher.publishLike(1L, 2L, "like")).isTrue();
    }
}
