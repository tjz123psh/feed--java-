package io.github.p_feed.config;

import java.nio.file.Path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.p_feed.tool.FilesTool;

// 将配置字符串转Path并建bean；


@Configuration(proxyBeanMethods = false)
public class UploadConfig{

    @Bean
    protected FilesTool filesTool(AppProps props){
        if(props.uploadRoot() == null || props.uploadRoot().isBlank()){
            return null;
        }

        return new FilesTool(Path.of(props.uploadRoot()));
    }
}
