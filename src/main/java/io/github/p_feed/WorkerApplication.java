package io.github.p_feed;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import io.github.p_feed.config.AppProps;

@SpringBootApplication
@EnableConfigurationProperties(AppProps.class)
// annotationClass：只注册带 @Mapper 的接口，避免把事件发布器等普通接口误当 Mapper
@MapperScan(basePackages = "io.github.p_feed", annotationClass = Mapper.class)
public class WorkerApplication {
    public static void main(String[] args){
        new SpringApplicationBuilder(WorkerApplication.class)
        .properties("spring.profiles.active=worker")
        .run(args);
    }
}
