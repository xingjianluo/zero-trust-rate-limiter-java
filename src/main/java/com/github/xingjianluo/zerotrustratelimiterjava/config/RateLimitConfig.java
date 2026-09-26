package com.github.xingjianluo.zerotrustratelimiterjava.config;

import com.github.xingjianluo.zerotrustratelimiterjava.core.PolicyHolder;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitPolicy;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 装配限流相关 Bean，并在启动时用初始配置构建版本 1 的策略快照。
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    @Bean
    public PolicyHolder policyHolder() {
        return new PolicyHolder();
    }

    /**
     * 启动初始化：发布版本 1 快照，确保第一个请求之前策略已确定。
     */
    @Bean
    public ApplicationRunner policyInitializer(RateLimitService rateLimitService,
                                               RateLimitProperties properties) {
        return new ApplicationRunner() {
            @Override
            public void run(ApplicationArguments args) {
                long version = rateLimitService.reload(properties);
                log.info("Initial rate-limit policy published as version {}", version);
            }
        };
    }
}
