package io.github.p_feed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(classes = WorkerApplication.class)
@ActiveProfiles("worker")
class WorkerApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void contextLoadsWithoutWebServer() {
        assertThat(applicationContext.getBean(WorkerApplication.class)).isNotNull();
    }
}
