package com.github.xingjianluo.zerotrustratelimiterjava.store;

import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 {@link ConcurrentHashMap} 的内存计数存储。
 *
 * <p>计数键由「策略版本 + 维度 + 维度键 + 窗口起点」构成：窗口切换自然产生新键；
 * 热更新产生新版本命名空间，旧版本计数保留但不再被引用，历史判定不被静默改写。
 *
 * <p>提供测试用故障注入开关（不可用/延迟），用于验证降级链路。
 */
@Component
public class InMemoryRateLimitStateStore implements RateLimitStateStore {

    private record CounterKey(long version, LimitDimension dimension, String dimensionKey, long windowStart) {
    }

    private final ConcurrentHashMap<CounterKey, AtomicLong> counters = new ConcurrentHashMap<>();
    private volatile boolean faultUnavailable;
    private volatile long faultDelayMillis;

    @Override
    public long incrementAndGet(long version, LimitDimension dimension, String dimensionKey, long windowStart) {
        if (faultUnavailable) {
            throw new StoreException(StoreException.Kind.STORE_UNAVAILABLE,
                    "injected fault: rate-limit state store unavailable", null);
        }
        sleepFaultDelay();
        CounterKey key = new CounterKey(version, dimension, dimensionKey, windowStart);
        long value = counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        // 控制无界增长：丢弃早于当前窗口起点的计数（窗口已切换，永不再用）。
        pruneOlderThan(windowStart);
        return value;
    }

    @Override
    public void pruneOlderThan(long minWindowStart) {
        for (CounterKey key : counters.keySet()) {
            if (key.windowStart() < minWindowStart) {
                counters.remove(key);
            }
        }
    }

    private void sleepFaultDelay() {
        long delay = faultDelayMillis;
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StoreException(StoreException.Kind.STORE_TIMEOUT,
                        "interrupted while applying injected delay", e);
            }
        }
    }

    /** 注入存储不可用故障。 */
    public void setFaultUnavailable(boolean fault) {
        this.faultUnavailable = fault;
    }

    /** 注入每次调用的额外延迟（毫秒），用于触发超时降级。 */
    public void setFaultDelayMillis(long delayMillis) {
        this.faultDelayMillis = delayMillis;
    }

    /** 仅测试/排障用：查看当前计数条目数。 */
    public int size() {
        return counters.size();
    }

    /** 仅测试用：清空全部计数与故障注入。 */
    public void reset() {
        counters.clear();
        faultUnavailable = false;
        faultDelayMillis = 0L;
    }
}
