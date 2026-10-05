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

@Component
public class JwtAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService service;
    private final TokenStore tokenStore;
    private final ObjectMapper objectMapper;

    public JwtAuthInterceptor(
            JwtService service, TokenStore tokenStore, ObjectMapper objectMapper) {
        this.service = service;
        this.tokenStore = tokenStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        String authorization = request.getHeader("authorization");
        if (authorization == null || authorization.isBlank()) {
            return reject(response, HttpStatus.UNAUTHORIZED, Errors.MISSING_AUTHORIZATION_HEADER);
        }

        if (!authorization.startsWith(BEARER_PREFIX)) {
            return reject(response, HttpStatus.UNAUTHORIZED, Errors.INVALID_AUTHORIZATION_HEADER);
        }

        String token = authorization.substring(BEARER_PREFIX.length());

        DecodedJWT decoded;
        try {
            decoded = service.parseToken(token);
        } catch (JWTVerificationException e) {
            return reject(response, HttpStatus.UNAUTHORIZED, Errors.INVALID_OR_EXPIRED_TOKEN);
        }

        long accountId = decoded.getClaim("account_id").asLong();

        String currentToken = tokenStore.current(accountId);
        if(currentToken == null || !currentToken.equals(token)){
            return reject(response, HttpStatus.UNAUTHORIZED, Errors.TOKEN_HAS_BEEN_REVOKED);
        }

        // 这里提取出来id方便后面Controller知道操作用户，以免重新解析这个token
        request.setAttribute("accountID", accountId);
        
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
