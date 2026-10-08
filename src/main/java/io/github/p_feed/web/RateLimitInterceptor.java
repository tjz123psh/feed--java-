package io.github.p_feed.web;

import java.util.Map;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import io.github.p_feed.infra.ApiError;
import io.github.p_feed.infra.Errors;
import io.github.p_feed.infra.RateLimiter;
import io.github.p_feed.infra.RateLimits;
import tools.jackson.databind.ObjectMapper;

@Component
public class RateLimitInterceptor implements HandlerInterceptor{

    private static final Map<String, String> URI_RULE = Map.ofEntries( //这个Map是装对应的uri获取对应的限流器名称
        Map.entry("/account/register", "account_register"),
        Map.entry("/account/login", "account_login"),
        Map.entry("/like/like", "like_write"),
        Map.entry("/like/unlike", "like_write"),
        Map.entry("/comment/publish", "comment_write"),
        Map.entry("/comment/delete", "comment_write"),
        Map.entry("/social/follow", "social_write"),
        Map.entry("/social/unfollow", "social_write")
    );

    private static final Map<String, RateLimits.Rule> RULES = RateLimits.ALL.stream().collect( // 这个Map是装对应的限流器名称获取相应的限流器规则
        Collectors.toMap(RateLimits.Rule::name, r -> r)
    );

    private final Map<String, RateLimiter> limiters; // 注入的 Bean 名 = 规则名; 注入限流器对象
    private final ObjectMapper objectMapper;

    public RateLimitInterceptor(Map<String, RateLimiter> limiters, ObjectMapper objectMapper) {
        this.limiters = limiters;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        String ruleName = URI_RULE.get(request.getRequestURI());
        if(ruleName == null){
            return true;
        }

        RateLimits.Rule rule = RULES.get(ruleName);
        RateLimiter limiter = limiters.get(ruleName);
        if(rule == null || limiter == null){
             // 配置缺失也按放行走（护栏，不是门票）
            return true;
        }

        String subject;
        if(rule.byIp()){
            subject = request.getRemoteAddr(); // 不解析XFF
        }else{
            Object accountId = request.getAttribute("accountID"); //前序jwt拦截器已经写入
            if(accountId == null){
                return true; //未登录: 跳过,不按IP兜底
            }
            subject = String.valueOf(accountId);
        }
        
        if(limiter.tryAcquire(subject)){
            return true;
        }
    
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(new ApiError(Errors.TOO_MANY_REQUESTS)));
        return false;
        
    }
    
    
}
