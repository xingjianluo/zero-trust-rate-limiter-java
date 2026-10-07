package com.github.xingjianluo.zerotrustratelimiterjava.core;

import com.github.xingjianluo.zerotrustratelimiterjava.model.Decision;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端重试幂等缓存：同一逻辑请求（相同请求标识）在短时间内重复到达时，
 * 复用首次判定的完整结论——是否放行、被哪一层拦下、原因、当前用量、策略版本全部一致，
 * 不会出现第一次放行、重试被拒的自相矛盾结果；重复请求不再访问后端状态存储。
 *
 * <p>语义保证：
 * <ul>
 *   <li>结论（含降级结论）在首次判定后不可变地缓存，TTL 由策略 {@code idempotentTtlMs} 决定；</li>
 *   <li>同一请求标识并发到达时按标识分段加锁，只有一个线程执行真实判定，其余线程等待并
 *       复用同一个结论，杜绝并发下产生两条不同结论；</li>
 *   <li>过期条目惰性清理，并有容量上限防止异常客户端制造无限多标识导致内存膨胀。</li>
 * </ul>
 *
 * <p>典型用法（在服务编排中）：
 * <pre>{@code
 * Optional<Decision> hit = cache.get(id);
 * if (hit.isPresent()) return hit.get();
 * Object lock = cache.lockFor(id);
 * synchronized (lock) {
 *     hit = cache.get(id);
 *     if (hit.isPresent()) return hit.get();
 *     Decision d = evaluate(...);
 *     cache.putCompleted(id, d, policy.idempotentTtlMs());
 *     lock.notifyAll();
 *     return d;
 * }
 * }</pre>
 */
@Component
public class DecisionCache {

    /** 单条缓存记录。 */
    private record Cached(Decision decision, long expiresAtMillis) {
    }

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final Clock clock;
    private final int maxEntries;
    private final AtomicLong probes = new AtomicLong();

    @Autowired
    public DecisionCache(Clock clock) {
        this(clock, 100_000);
    }

    DecisionCache(Clock clock, int maxEntries) {
        this.clock = clock;
        this.maxEntries = maxEntries;
    }

    /**
     * 尝试取未过期的缓存结论；命中过期项时顺手清理。
     */
    public Optional<Decision> get(String requestId) {
        Cached cached = cache.get(requestId);
        if (cached == null) {
            return Optional.empty();
        }
        if (clock.nowMillis() >= cached.expiresAtMillis()) {
            cache.remove(requestId, cached);
            return Optional.empty();
        }
        return Optional.of(cached.decision());
    }

    /**
     * 获取请求标识对应的分段锁对象（同一标识恒为同一对象）。
     * 调用方在 {@code synchronized} 块内做双重检查与真实判定。
     */
    public Object lockFor(String requestId) {
        occasionallyPrune();
        return locks.computeIfAbsent(requestId, k -> new Object());
    }

    /** 写入首次判定结论。须在持有 {@link #lockFor} 锁时调用。 */
    public void putCompleted(String requestId, Decision decision, long ttlMillis) {
        cache.put(requestId, new Cached(decision, clock.nowMillis() + ttlMillis));
    }

    /** 清空（测试用）。 */
    public void reset() {
        cache.clear();
    }

    /**
     * 惰性清理：周期性扫描过期项；容量超限时再补一次尽力回收。
     */
    private void occasionallyPrune() {
        long now = clock.nowMillis();
        if (probes.incrementAndGet() % 256 == 0) {
            cache.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
        }
        if (cache.size() >= maxEntries) {
            cache.entrySet().removeIf(e -> e.getValue().expiresAtMillis() <= now);
        }
    }
}
