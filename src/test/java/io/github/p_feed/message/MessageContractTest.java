package io.github.p_feed.message;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 2 步私信契约：send 返回带 id 的实体（from_id 只信登录态、content trim 后落库）；
 * list 双向可见、最新 50 条、空会话是 [] 而不是 null；to_id / peer_id / 空白 content 的
 * 400 文案逐字；/message/* 被鉴权拦截器罩住（401 文案是 missing authorization header，
 * 用来防止漏改 WebConfig 的路径注册）。
 */
@SpringBootTest(classes = ApiApplication.class)
@AutoConfigureMockMvc
class MessageContractTest {

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountMapper accountMapper;
    @Autowired private MessageMapper messageMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void send_returnsEntity_andListSeesItFromBothSides() throws Exception {
        Probe a = registerAndLogin();
        Probe b = registerAndLogin();
        String content = "hello-" + UUID.randomUUID().toString().substring(0, 8);

        String sent = mockMvc.perform(post("/message/send")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(JSON)
                        .content("{\"to_id\":" + b.id() + ",\"content\":\"" + content + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.from_id").value((int) a.id()))
                .andExpect(jsonPath("$.to_id").value((int) b.id()))
                .andExpect(jsonPath("$.content").value(content))
                .andExpect(jsonPath("$.is_read").value(false))
                .andExpect(jsonPath("$.created_at").exists())
                .andReturn().getResponse().getContentAsString();
        int messageId = objectMapper.readTree(sent).get("id").asInt();

        // 对方的视角（他→我方向）
        mockMvc.perform(post("/message/list")
                        .header("Authorization", "Bearer " + b.token())
                        .contentType(JSON)
                        .content("{\"peer_id\":" + a.id() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].id").value(messageId));

        // 自己的视角（我→他方向）
        mockMvc.perform(post("/message/list")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(JSON)
                        .content("{\"peer_id\":" + b.id() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].id").value(messageId));

        cleanup(a.id(), b.id());
    }

    @Test
    void send_trimsContent_andAcceptsUnknownRecipient() throws Exception {
        Probe a = registerAndLogin();

        // content 前后空白会被 trim 后落库（原版 repo.Send 的 strings.TrimSpace）
        String raw = "  padded-" + UUID.randomUUID().toString().substring(0, 8) + "  ";
        mockMvc.perform(post("/message/send")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(JSON)
                        .content("{\"to_id\":999000999,\"content\":\"" + raw + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(raw.trim()));

        cleanup(a.id());
    }

    @Test
    void send_withZeroToIdOrBlankContent_returns400Verbatim() throws Exception {
        Probe a = registerAndLogin();

        mockMvc.perform(post("/message/send")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(JSON)
                        .content("{\"to_id\":0,\"content\":\"hi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(Errors.TO_ID_AND_CONTENT_ARE_REQUIRED));

        mockMvc.perform(post("/message/send")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(JSON)
                        .content("{\"to_id\":123,\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(Errors.TO_ID_AND_CONTENT_ARE_REQUIRED));

        cleanup(a.id());
    }

    @Test
    void list_withZeroPeer_returns400Verbatim() throws Exception {
        Probe a = registerAndLogin();

        mockMvc.perform(post("/message/list")
                        .header("Authorization", "Bearer " + a.token())
                        .contentType(JSON)
                        .content("{\"peer_id\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(Errors.PEER_ID_IS_REQUIRED));

        cleanup(a.id());
    }

    @Test
    void list_withStranger_returnsEmptyArrayNotNull() throws Exception {
        Probe a = registerAndLogin();
        Probe b = registerAndLogin();

        mockMvc.perform(post("/message/list")
                        .header("Authorization", "Bearer " + b.token())
                        .contentType(JSON)
                        .content("{\"peer_id\":" + a.id() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isArray())
                .andExpect(jsonPath("$.messages").isEmpty());

        cleanup(a.id(), b.id());
    }

    @Test
    void send_withoutToken_isRejectedByInterceptor() throws Exception {
        mockMvc.perform(post("/message/send")
                        .contentType(JSON)
                        .content("{\"to_id\":1,\"content\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(Errors.MISSING_AUTHORIZATION_HEADER));
    }

    // ---- 工具 ----

    private record Probe(long id, String token) {}

    private Probe registerAndLogin() throws Exception {
        String name = "ct_msg_" + UUID.randomUUID().toString().substring(0, 8);
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

    private void cleanup(long... accountIds) {
        List<Long> ids = Arrays.stream(accountIds).boxed().toList();
        messageMapper.delete(new LambdaQueryWrapper<Message>()
                .and(w -> w.in(Message::getFromId, ids).or().in(Message::getToId, ids)));
    }
}
