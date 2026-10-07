package io.github.p_feed.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.p_feed.ApiApplication;
import io.github.p_feed.account.Account;
import io.github.p_feed.account.AccountMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 5 步 SseHub / /notification/stream 契约（生产者第 6 步才接，这里直接调 Hub.push）：
 * 建流 200 + text/event-stream 且保持 async；同一账号多条连接都收到 data 帧；
 * 只推本人；同一连接内按入队顺序到达。
 */
@SpringBootTest(classes = ApiApplication.class)
@AutoConfigureMockMvc
class SseStreamTest {

    private static final String STREAM = "/notification/stream";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired private MockMvc mockMvc;
    @Autowired private SseHub sseHub;
    @Autowired private AccountMapper accountMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void streamOpens_asEventStreamAndStaysAsync() throws Exception {
        Probe me = registerAndLogin();

        MvcResult result = mockMvc.perform(get(STREAM).param("token", me.token()))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();

        assertThat(result.getResponse().getContentType()).contains("text/event-stream");
    }

    @Test
    void push_fansOutToAllConnectionsOfSameUser() throws Exception {
        Probe me = registerAndLogin();
        MvcResult c1 = openStream(me.token());
        MvcResult c2 = openStream(me.token());

        sseHub.push(me.id(), notification("like", "probe"));

        assertThat(pollFor(c1, "\"type\":\"like\"")).isTrue();
        assertThat(pollFor(c2, "\"type\":\"like\"")).isTrue();
        assertThat(c1.getResponse().getContentAsString()).contains("data:");
    }

    @Test
    void push_isScopedToTheUser() throws Exception {
        Probe me = registerAndLogin();
        Probe other = registerAndLogin();
        MvcResult mine = openStream(me.token());

        sseHub.push(other.id(), notification("follow", "probe"));
        Thread.sleep(400); // 给"推错人"留出可能出现的窗口
        assertThat(mine.getResponse().getContentAsString()).doesNotContain("\"type\":\"follow\"");

        sseHub.push(me.id(), notification("like", "probe")); // 反证：推给自己能到
        assertThat(pollFor(mine, "\"type\":\"like\"")).isTrue();
    }

    @Test
    void push_sameConnection_arrivesInQueueOrder() throws Exception {
        Probe me = registerAndLogin();
        MvcResult mine = openStream(me.token());

        sseHub.push(me.id(), notification("like", "m1"));
        sseHub.push(me.id(), notification("like", "m2"));
        sseHub.push(me.id(), notification("like", "m3"));

        assertThat(pollFor(mine, "\"content\":\"m3\"")).isTrue();
        String body = mine.getResponse().getContentAsString();
        assertThat(body.indexOf("\"content\":\"m1\"")).isLessThan(body.indexOf("\"content\":\"m2\""));
        assertThat(body.indexOf("\"content\":\"m2\"")).isLessThan(body.indexOf("\"content\":\"m3\""));
    }

    // ---- 工具 ----

    private record Probe(long id, String token) {}

    private MvcResult openStream(String token) throws Exception {
        return mockMvc.perform(get(STREAM).param("token", token))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andReturn();
    }

    private boolean pollFor(MvcResult result, String needle) throws Exception {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (result.getResponse().getContentAsString().contains(needle)) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    private Notification notification(String type, String content) {
        Notification n = new Notification();
        n.setId(1L);
        n.setRecipientId(1L);
        n.setSenderId(2L);
        n.setType(type);
        n.setTargetId(3L);
        n.setContent(content);
        n.setIsRead(false);
        n.setCreatedAt(OffsetDateTime.now());
        return n;
    }

    private Probe registerAndLogin() throws Exception {
        String name = "ct_sse_" + UUID.randomUUID().toString().substring(0, 8);
        mockMvc.perform(post("/account/register")
                        .contentType(JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"pw123456\"}"))
                .andExpect(status().isOk());
        String body = mockMvc.perform(post("/account/login")
                        .contentType(JSON)
                        .content("{\"username\":\"" + name + "\",\"password\":\"pw123456\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(body).get("token").asText();
        Account account = accountMapper.selectOne(
                new LambdaQueryWrapper<Account>().eq(Account::getUsername, name));
        return new Probe(account.getId(), token);
    }
}
