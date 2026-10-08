package io.github.p_feed.web;

import io.github.p_feed.ApiApplication;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.infra.RateLimiter;
import io.github.p_feed.infra.RateLimits;
import io.github.p_feed.infra.RedisRateLimiter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 第 9 步限流契约：固定窗口（10 次登录额度，第 11 次 429 且响应体逐字、无 Retry-After）；
 * 账号维度未登录（读不到 accountID）→ 跳过、不按 IP 兜底；Redis 不可用 → fail-open 放行。
 * 测试会先 DEL 计数器键，跑完再 DEL——不会占用你在 127.0.0.1 上的真实登录额度。
 */
@SpringBootTest(classes = ApiApplication.class, properties = "feed.ratelimit.enabled=true")
@AutoConfigureMockMvc
class RateLimitTest {

    private static final String LOGIN_KEY = "feedsystem:ratelimit:account_login:127.0.0.1";
    private static final String LIKE_PROBE_KEY = "feedsystem:ratelimit:like_write:42";
    private static final String LIKE_IP_KEY = "feedsystem:ratelimit:like_write:127.0.0.1";
    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Autowired private MockMvc mockMvc;
    @Autowired private RateLimitInterceptor rateLimitInterceptor;
    @Autowired private StringRedisTemplate redis;

    @Test
    void login_11thAttemptInWindow_is429_exactBody_noRetryAfter() throws Exception {
        redis.delete(LOGIN_KEY);

        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/account/login")
                            .contentType(JSON)
                            .content("{\"username\":\"ct_rl_probe\",\"password\":\"x\"}"))
                    .andExpect(status().isBadRequest());
        }

        MvcResult blocked = mockMvc.perform(post("/account/login")
                        .contentType(JSON)
                        .content("{\"username\":\"ct_rl_probe\",\"password\":\"x\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value(Errors.TOO_MANY_REQUESTS))
                .andExpect(header().doesNotExist("Retry-After"))
                .andReturn();

        assertThat(blocked.getResponse().getContentAsString())
                .as("429 响应体逐字（照抄原版）")
                .isEqualTo("{\"error\":\"too many requests\"}");
    }

    @Test
    void accountDimension_withoutLogin_isSkipped_notCountedByIp() throws Exception {
        redis.delete(LIKE_IP_KEY);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/like/like");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(rateLimitInterceptor.preHandle(request, response, new Object()))
                .as("账号维度取不到 accountID → 直接放行")
                .isTrue();
        assertThat(redis.hasKey(LIKE_IP_KEY))
                .as("未登录不应按 IP 兜底计数")
                .isFalse();
    }

    @Test
    void accountDimension_31stRequest_is429() throws Exception {
        redis.delete(LIKE_PROBE_KEY);

        for (int i = 0; i < 30; i++) {
            MockHttpServletRequest request = likeRequestWithAccount(42L);
            assertThat(rateLimitInterceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                    .as("额度内第 %d 次应放行", i + 1)
                    .isTrue();
        }

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        assertThat(rateLimitInterceptor.preHandle(likeRequestWithAccount(42L), blocked, new Object()))
                .as("第 31 次应超限")
                .isFalse();
        assertThat(blocked.getStatus()).isEqualTo(429);
        assertThat(blocked.getContentAsString()).isEqualTo("{\"error\":\"too many requests\"}");
    }

    @Test
    void redisUnavailable_isFailOpen() {
        StringRedisTemplate broken = new StringRedisTemplate() {
            @Override
            public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                throw new RedisConnectionFailureException("connection refused (probe)");
            }
        };
        RateLimits.Rule rule = RateLimits.ALL.stream()
                .filter(r -> r.name().equals("account_login"))
                .findFirst()
                .orElseThrow();

        RateLimiter limiter = new RedisRateLimiter(broken, rule);

        assertThat(limiter.tryAcquire("127.0.0.1"))
                .as("Redis 不可用必须放行（fail-open：护栏不是门票）")
                .isTrue();
    }

    private MockHttpServletRequest likeRequestWithAccount(long accountId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/like/like");
        request.setAttribute("accountID", accountId);
        return request;
    }

    @AfterEach
    void cleanup() {
        redis.delete(LOGIN_KEY);
        redis.delete(LIKE_PROBE_KEY);
        redis.delete(LIKE_IP_KEY);
    }
}
