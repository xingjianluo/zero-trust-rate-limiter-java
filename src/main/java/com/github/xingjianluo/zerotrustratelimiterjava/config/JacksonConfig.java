package com.github.xingjianluo.zerotrustratelimiterjava.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 让管理端 JSON 的 {@link Duration} 同时接受 Spring Boot 简写（"500ms"/"1m"）、
 * ISO-8601（"PT0.5S"）与毫秒数字，与 application.properties 的配置写法保持一致。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer durationCustomizer() {
        SimpleModule durationModule = new SimpleModule();
        durationModule.addDeserializer(Duration.class, new SimpleDurationDeserializer());
        return builder -> builder.modulesToInstall(new JavaTimeModule(), durationModule);
    }
}
