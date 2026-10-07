package io.github.p_feed.notification;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.p_feed.ApiApplication;
import io.github.p_feed.infra.Errors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 4 步 SSE 鉴权契约：
 * token 先取查询参数 ?token= 再看 Authorization；401 文案逐字 missing token / invalid token；
 * 查询参数优先（有值就用，坏了也不回退）。
 * 决策 b（白名单）：登出后的 token 立刻 401 token has been revoked（参考实现此时仍放行）。
 * 第 5 步之前 /notification/stream 没有 handler，本测试只覆盖"被拦截"的分支（不会开流）。
 */
@SpringBootTest(classes = ApiApplication.class)
@AutoConfigureMockMvc
class SseAuthTest {

    private static final String STREAM = "/notification/stream";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void noToken_is401_missingToken() throws Exception {
        mockMvc.perform(get(STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(Errors.MISSING_TOKEN));
    }

    @Test
    void garbageTokenInQuery_is401_invalidToken() throws Exception {
        mockMvc.perform(get(STREAM).param("token", "not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(Errors.INVALID_TOKEN));
    }

    @Test
    void garbageTokenInHeader_is401_invalidToken() throws Exception {
        mockMvc.perform(get(STREAM).header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(Errors.INVALID_TOKEN));
    }

    @Test
    void queryParamTakesPrecedence_overValidHeader() throws Exception {
        String token = registerAndLogin();

        mockMvc.perform(get(STREAM)
                        .param("token", "not-a-jwt")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(Errors.INVALID_TOKEN));
    }

    @Test
    void revokedToken_is401_tokenHasBeenRevoked() throws Exception {
        String token = registerAndLogin();

        mockMvc.perform(post("/account/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get(STREAM).param("token", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value(Errors.TOKEN_HAS_BEEN_REVOKED));
    }

    private String registerAndLogin() throws Exception {
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
        return objectMapper.readTree(body).get("token").asText();
    }
}
