package io.github.p_feed.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.p_feed.ApiApplication;
import io.github.p_feed.account.Account;
import io.github.p_feed.account.AccountMapper;
import io.github.p_feed.infra.Errors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 3 步通知读端契约（生产者还没接，数据由测试手工造）：
 * list 只回本人收件、created_at 倒序、空为 []；unreadCount 字段逐字 count；
 * markRead 单条只能标自己收件箱的（标别人的不报错也不生效）、空 body/{} = 标全部；
 * /notification/* 已被拦截器罩住（401 文案是 missing authorization header）。
 */
@SpringBootTest(classes = ApiApplication.class)
@AutoConfigureMockMvc
class NotificationReadTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private NotificationMapper notificationMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void list_returnsOnlyMine_newestFirst() throws Exception {
        Probe me = registerAndLogin();
        Probe other = registerAndLogin();

        insert(other.id(), me.id(), "like", false, OffsetDateTime.now().minusSeconds(2));
        insert(other.id(), me.id(), "comment", true, OffsetDateTime.now());
        insert(me.id(), other.id(), "follow", false, OffsetDateTime.now()); // 别人的收件，不该出现

        mockMvc.perform(post("/notification/list")
                        .header("Authorization", "Bearer " + me.token())
                        .contentType(JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.length()").value(2))
                .andExpect(jsonPath("$.notifications[0].type").value("comment")) // 新的在前
                .andExpect(jsonPath("$.notifications[0].recipient_id").value((int) me.id()))
                .andExpect(jsonPath("$.notifications[0].is_read").value(true))
                .andExpect(jsonPath("$.notifications[1].type").value("like"));

        mockMvc.perform(post("/notification/list")
                        .header("Authorization", "Bearer " + other.token())
                        .contentType(JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications.length()").value(1))
                .andExpect(jsonPath("$.notifications[0].type").value("follow"));

        cleanup(me.id(), other.id());
    }

    @Test
    void emptyInbox_isEmptyArray() throws Exception {
        Probe me = registerAndLogin();

        mockMvc.perform(post("/notification/list")
                        .header("Authorization", "Bearer " + me.token())
                        .contentType(JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notifications").isArray())
                .andExpect(jsonPath("$.notifications").isEmpty());

        cleanup(me.id());
    }

    @Test
    void markRead_singleOnlyTouchesMyInbox() throws Exception {
        Probe me = registerAndLogin();
        Probe other = registerAndLogin();
        long mine = insert(other.id(), me.id(), "like", false, OffsetDateTime.now());
        long alsoMine = insert(other.id(), me.id(), "comment", false, OffsetDateTime.now());
        long notMine = insert(me.id(), other.id(), "follow", false, OffsetDateTime.now());

        // 标别人的：不报错、也不生效
        mockMvc.perform(post("/notification/markRead")
                        .header("Authorization", "Bearer " + me.token())
                        .contentType(JSON)
                        .content("{\"id\":" + notMine + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("ok"));
        assertThat(readFlag(notMine)).isFalse();

        // 标自己的单条：只动这一条
        mockMvc.perform(post("/notification/markRead")
                        .header("Authorization", "Bearer " + me.token())
                        .contentType(JSON)
                        .content("{\"id\":" + mine + "}"))
                .andExpect(status().isOk());
        assertThat(readFlag(mine)).isTrue();
        assertThat(readFlag(alsoMine)).isFalse();

        mockMvc.perform(post("/notification/unreadCount")
                        .header("Authorization", "Bearer " + me.token())
                        .contentType(JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));

        cleanup(me.id(), other.id());
    }

    @Test
    void markRead_emptyBodyOrBraces_marksAllMine() throws Exception {
        Probe me = registerAndLogin();
        insert(me.id(), me.id(), "like", false, OffsetDateTime.now());
        insert(me.id(), me.id(), "comment", false, OffsetDateTime.now());

        // {} 形态
        mockMvc.perform(post("/notification/markRead")
                        .header("Authorization", "Bearer " + me.token())
                        .contentType(JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("ok"));
        mockMvc.perform(post("/notification/unreadCount")
                        .header("Authorization", "Bearer " + me.token())
                        .contentType(JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));

        // 空 body（完全不带）形态
        insert(me.id(), me.id(), "follow", false, OffsetDateTime.now());
        mockMvc.perform(post("/notification/markRead")
                        .header("Authorization", "Bearer " + me.token()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/notification/unreadCount")
                        .header("Authorization", "Bearer " + me.token())
                        .contentType(JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));

        cleanup(me.id());
    }

    @Test
    void withoutToken_isRejectedByInterceptor() throws Exception {
        mockMvc.perform(post("/notification/list").contentType(JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(Errors.MISSING_AUTHORIZATION_HEADER));
    }

    // ---- 工具 ----

    private record Probe(long id, String token) {}

    private Probe registerAndLogin() throws Exception {
        String name = "ct_ntf_" + UUID.randomUUID().toString().substring(0, 8);
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

    private long insert(long senderId, long recipientId, String type, boolean read, OffsetDateTime at) {
        Notification n = new Notification();
        n.setSenderId(senderId);
        n.setRecipientId(recipientId);
        n.setType(type);
        n.setTargetId(1L);
        n.setContent("probe");
        n.setIsRead(read);
        n.setCreatedAt(at);
        notificationMapper.insert(n);
        return n.getId();
    }

    private boolean readFlag(long id) {
        return Boolean.TRUE.equals(notificationMapper.selectById(id).getIsRead());
    }

    private void cleanup(long... accountIds) {
        List<Long> ids = Arrays.stream(accountIds).boxed().toList();
        notificationMapper.delete(new LambdaQueryWrapper<Notification>()
                .and(w -> w.in(Notification::getRecipientId, ids).or().in(Notification::getSenderId, ids)));
    }
}
