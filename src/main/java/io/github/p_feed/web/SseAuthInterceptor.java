package io.github.p_feed.web;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;

import io.github.p_feed.auth.JwtService;
import io.github.p_feed.auth.TokenStore;
import io.github.p_feed.infra.ApiError;
import io.github.p_feed.infra.Errors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import tools.jackson.databind.ObjectMapper;

// SSE 组单独一个拦截器：EventSource 不能带头，token 先取 ?token=，
// 取不到再看 Authorization；401 只有两句逐字文案（决策 b 另加白名单比对）。

@Component
public class SseAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService service;
    private final TokenStore tokenStore;
    private final ObjectMapper objectMapper;

    public SseAuthInterceptor(
            JwtService service, TokenStore tokenStore, ObjectMapper objectMapper) {
        this.service = service;
        this.tokenStore = tokenStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        // 先查询参数；取不到才看 Authorization: Bearer（有值就用，坏了不回退）
        String token = request.getParameter("token");
        if (token == null || token.isBlank()) {
            String authorization = request.getHeader("authorization");
            if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
                token = authorization.substring(BEARER_PREFIX.length());
            }
        }

        if (token == null || token.isBlank()) {
            return reject(response, HttpStatus.UNAUTHORIZED, Errors.MISSING_TOKEN);
        }

        DecodedJWT decoded;
        try {
            decoded = service.parseToken(token);
        } catch (JWTVerificationException e) {
            return reject(response, HttpStatus.UNAUTHORIZED, Errors.INVALID_TOKEN);
        }

        Long accountId = decoded.getClaim("account_id").asLong();

        // 白名单比对：登出/换发后立即失效；仅验签会放任旧 token 到自然过期
        String currentToken = tokenStore.current(accountId);
        if(currentToken == null || !currentToken.equals(token)){
            return reject(response, HttpStatus.UNAUTHORIZED, Errors.TOKEN_HAS_BEEN_REVOKED);
        }
 
        request.setAttribute("accountID", accountId);;
        return true;
    }

    private boolean reject(HttpServletResponse response, HttpStatus status, String message)
            throws Exception {
        response.setStatus(status.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(new ApiError(message)));
        return false;
    }
}
