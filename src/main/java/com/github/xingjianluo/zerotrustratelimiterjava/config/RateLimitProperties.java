package com.github.xingjianluo.zerotrustratelimiterjava.config;

import com.github.xingjianluo.zerotrustratelimiterjava.model.FailStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration knobs under the {@code rate-limiter} prefix. */
@ConfigurationProperties(prefix = "rate-limiter")
public class RateLimitProperties {

    private String dataDir = "data";
    private FailStrategy failStrategy = FailStrategy.CLOSED;
    private long storeTimeoutMillis = 200;
    private long dedupTtlMillis = 30_000;
    private int decisionLogCapacity = 1024;

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public FailStrategy getFailStrategy() {
        return failStrategy;
    }

    public void setFailStrategy(FailStrategy failStrategy) {
        this.failStrategy = failStrategy;
    }

    public long getStoreTimeoutMillis() {
        return storeTimeoutMillis;
    }

    public void setStoreTimeoutMillis(long storeTimeoutMillis) {
        this.storeTimeoutMillis = storeTimeoutMillis;
    }

    public long getDedupTtlMillis() {
        return dedupTtlMillis;
    }

    public void setDedupTtlMillis(long dedupTtlMillis) {
        this.dedupTtlMillis = dedupTtlMillis;
    }

    public int getDecisionLogCapacity() {
        return decisionLogCapacity;
    }

    public void setDecisionLogCapacity(int decisionLogCapacity) {
        this.decisionLogCapacity = decisionLogCapacity;
    }
}
