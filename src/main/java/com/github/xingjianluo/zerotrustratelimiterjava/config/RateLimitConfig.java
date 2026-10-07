package com.github.xingjianluo.zerotrustratelimiterjava.config;

import com.github.xingjianluo.zerotrustratelimiterjava.core.Clock;
import com.github.xingjianluo.zerotrustratelimiterjava.core.PolicyHolder;
import com.github.xingjianluo.zerotrustratelimiterjava.core.QuotaViewService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitPolicy;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService;
import com.github.xingjianluo.zerotrustratelimiterjava.store.FileRateLimitStateStore;
import com.github.xingjianluo.zerotrustratelimiterjava.store.InMemoryRateLimitStateStore;
import com.github.xingjianluo.zerotrustratelimiterjava.store.RateLimitStateStore;
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
     * 配额计数存储：配置了 {@code ratelimit.storage.rate-state-file} 时使用文件持久化实现，
     * 进程重启后当前窗口用量延续；为空时退化为纯内存实现。
     */
    @Bean
    public RateLimitStateStore rateLimitStateStore(
            Clock clock, RateLimitProperties properties) {
        String file = properties.getStorage().getRateStateFile();
        if (file != null && !file.isBlank()) {
            log.info("Rate-limit counters persisted to {}", file);
            return new FileRateLimitStateStore(clock, file);
        }
        return new InMemoryRateLimitStateStore();
    }

    /** 只读配额视图：与入口判定共用同一计数存储与策略快照。 */
    @Bean
    public QuotaViewService quotaViewService(RateLimitService rateLimitService,
                                             com.github.xingjianluo.zerotrustratelimiterjava.core.BanService banService,
                                             Clock clock) {
        return new QuotaViewService(rateLimitService, banService, clock);
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
                RateLimitProperties.Replay replay = properties.getReplay();
                long ttlMs = replay.getTtl() == null ? 5000L : replay.getTtl().toMillis();
                rateLimitService.configureReplay(
                        replay.isEnabled(), ttlMs, Math.max(1, replay.getCapacity()));
                log.info("Initial rate-limit policy published as version {} (replay enabled={}, ttlMs={})",
                        version, replay.isEnabled(), replay.isEnabled() ? ttlMs : 0L);
            }
        };
    }
}
