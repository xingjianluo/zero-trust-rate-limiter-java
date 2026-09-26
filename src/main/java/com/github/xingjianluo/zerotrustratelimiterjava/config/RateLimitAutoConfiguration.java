package com.github.xingjianluo.zerotrustratelimiterjava.config;

import com.github.xingjianluo.zerotrustratelimiterjava.core.DecisionLog;
import com.github.xingjianluo.zerotrustratelimiterjava.core.DecisionMetrics;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.SystemTimeSource;
import com.github.xingjianluo.zerotrustratelimiterjava.core.TimeSource;
import com.github.xingjianluo.zerotrustratelimiterjava.policy.FilePolicyStore;
import com.github.xingjianluo.zerotrustratelimiterjava.policy.PolicyManager;
import com.github.xingjianluo.zerotrustratelimiterjava.policy.PolicyStore;
import com.github.xingjianluo.zerotrustratelimiterjava.store.BanStore;
import com.github.xingjianluo.zerotrustratelimiterjava.store.FaultState;
import com.github.xingjianluo.zerotrustratelimiterjava.store.FileBanStore;
import com.github.xingjianluo.zerotrustratelimiterjava.store.InMemoryCounterStore;
import com.github.xingjianluo.zerotrustratelimiterjava.web.RateLimitFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/** Wires the adaptive rate limiter beans using only local file/in-memory state. */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitAutoConfiguration {

    @Bean
    public TimeSource timeSource() {
        return new SystemTimeSource();
    }

    @Bean
    public FaultState faultState() {
        return new FaultState();
    }

    @Bean
    public InMemoryCounterStore counterStore(FaultState faultState) {
        return new InMemoryCounterStore(faultState);
    }

    @Bean
    public BanStore banStore(RateLimitProperties properties, FaultState faultState) {
        Path file = Path.of(properties.getDataDir(), "bans.json");
        return new FileBanStore(file, faultState);
    }

    @Bean
    public PolicyStore policyStore(RateLimitProperties properties,
                                   FaultState faultState) {
        Path file = Path.of(properties.getDataDir(), "policies.jsonl");
        return new FilePolicyStore(file, faultState);
    }

    @Bean
    public PolicyManager policyManager(PolicyStore policyStore, TimeSource timeSource) {
        PolicyManager manager = new PolicyManager(policyStore, timeSource);
        manager.seedIfEmpty(DefaultPolicyFactory.create(timeSource));
        return manager;
    }

    @Bean
    public DecisionMetrics decisionMetrics() {
        return new DecisionMetrics();
    }

    @Bean
    public DecisionLog decisionLog(RateLimitProperties properties) {
        return new DecisionLog(properties.getDecisionLogCapacity());
    }

    @Bean
    public RateLimitService rateLimitService(InMemoryCounterStore counterStore,
                                             BanStore banStore,
                                             PolicyManager policyManager,
                                             TimeSource timeSource,
                                             RateLimitProperties properties,
                                             DecisionMetrics metrics,
                                             DecisionLog decisionLog) {
        return new RateLimitService(
                counterStore, banStore, policyManager, timeSource,
                properties.getFailStrategy(), properties.getStoreTimeoutMillis(),
                properties.getDedupTtlMillis(), metrics, decisionLog);
    }

    @Bean
    public FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(
            RateLimitService rateLimitService) {
        FilterRegistrationBean<RateLimitFilter> registration =
                new FilterRegistrationBean<>();
        registration.setFilter(new RateLimitFilter(rateLimitService));
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        return registration;
    }
}
