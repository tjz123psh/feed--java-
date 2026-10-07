package io.github.p_feed.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 注册区
@Configuration
class WebConfig implements WebMvcConfigurer {

    private final JwtAuthInterceptor jwtAuthInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final SoftJwtAuthInterceptor softJwtAuthInterceptor;
    private final SseAuthInterceptor sseAuthInterceptor;

    public WebConfig(JwtAuthInterceptor jwtAuthInterceptor ,RateLimitInterceptor rateLimitInterceptor, SoftJwtAuthInterceptor softJwtAuthInterceptor, SseAuthInterceptor sseAuthInterceptor){
        this.jwtAuthInterceptor = jwtAuthInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.softJwtAuthInterceptor = softJwtAuthInterceptor;
        this.sseAuthInterceptor = sseAuthInterceptor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/static/**")
            .addResourceLocations("file:" + System.getProperty("user.dir") + "/data/uploads/"); 
        
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtAuthInterceptor)
            .addPathPatterns("/account/logout","/account/rename","/account/updateProfile","/account/uploadAvatar")
            .addPathPatterns("/video/uploadVideo","/video/uploadCover","/video/publish","/video/chunk/init","/video/chunk/upload","/video/chunk/status","/video/chunk/complete")
            .addPathPatterns("/comment/publish","/comment/delete")
            .addPathPatterns("/like/like","/like/unlike","/like/isLiked","/like/listMyLikedVideos")
            .addPathPatterns("/social/follow","/social/unfollow","/social/getAllFollowers","/social/getAllVloggers","/social/getCounts")
            .addPathPatterns("/feed/listByFollowing")
            .addPathPatterns("/message/send", "/message/list")
            .addPathPatterns("/notification/markRead", "/notification/list", "/notification/unreadCount");

        registry.addInterceptor(sseAuthInterceptor)
            .addPathPatterns("/notification/stream");

        registry.addInterceptor(rateLimitInterceptor)
            .addPathPatterns("/account/register","/account/login")
            .addPathPatterns("/comment/publish","/comment/delete","/like/like","/like/unlike")
            .addPathPatterns("/social/follow","/social/unfollow");

        registry.addInterceptor(softJwtAuthInterceptor)
            .addPathPatterns("/feed/listLatest","/feed/listLikesCount","/feed/listByPopularity","/feed/listByTag");
    }

    
    
}
