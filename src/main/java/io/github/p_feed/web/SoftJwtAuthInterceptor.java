package io.github.p_feed.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;



@Component
public class SoftJwtAuthInterceptor implements HandlerInterceptor{

    private final JwtAuthInterceptor strict;

    public SoftJwtAuthInterceptor(JwtAuthInterceptor strict) {
        this.strict = strict;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        String authorization = request.getHeader("authorization");
        if(authorization == null || authorization.isBlank()){
            return true;
        }

        return strict.preHandle(request, response, handler);
    }

    
}
