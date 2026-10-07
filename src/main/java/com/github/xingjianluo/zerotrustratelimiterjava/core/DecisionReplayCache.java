package com.github.xingjianluo.zerotrustratelimiterjava.core;

import com.github.xingjianluo.zerotrustratelimiterjava.model.Decision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 判定结论幂等缓存：同一请求标识在 TTL 内重复到达时，直接返回首次判定结论。
 *
 * <p>语义保证：
 * <ul>
 *   <li>首次结论（放行/被某层拦下/封禁/降级）整体缓存，重试拿到的
 *       allowed、拦下分层、原因、当前用量、策略版本与首次完全一致；</li>
 *   <li>命中缓存时不再访问任何后端状态存储，避免重试放大存储压力；</li>
 *   <li>同一请求标识并发到达时串行化首次计算，只产生一次结论、一次计数；</li>
 *   <li>TTL 过期后按新请求重新判定（窗口可能已切换）。</li>
 * </ul>
 */
public class DecisionReplayCache {

    private static final Logger log = LoggerFactory.getLogger(DecisionReplayCache.class);

    private record Cached(Decision decision, long expiresAt) {
    }

    private final Clock clock;
    private volatile long ttlMillis;
    private volatile int capacity;
    // 访问序 LinkedHashMap 做 LRU；所有访问经 lock 串行（仅内存操作，开销极小）
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);
    private final ReentrantLock lock = new ReentrantLock();

    public DecisionReplayCache(Clock clock, long ttlMillis, int capacity) {
        this.clock = clock;
        this.ttlMillis = ttlMillis;
        this.capacity = capacity;
    }

    /**
     * 读取未过期的首次结论；不存在或已过期返回 null（过期条目不主动清理，等被覆盖/LRU 淘汰）。
     */
    public Decision get(String requestId) {
        lock.lock();
        try {
            Cached c = cache.get(requestId);
            if (c == null) {
                return null;
            }
            if (clock.nowMillis() >= c.expiresAt()) {
                cache.remove(requestId);
                return null;
            }
            return c.decision();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 以 requestId 为键发布首次结论；若并发期间已有其它线程发布，保留先到结论。
     */
    public void put(String requestId, Decision decision) {
        lock.lock();
        try {
            cache.putIfAbsent(requestId, new Cached(decision, clock.nowMillis() + ttlMillis));
            while (cache.size() > capacity) {
                Map.Entry<String, Cached> eldest = cache.entrySet().iterator().next();
                if (eldest == null) {
                    break;
                }
                cache.remove(eldest.getKey());
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 获取某请求标识对应的串行锁（同键首次计算去重用）。粗粒度分段即可。
     */
    public Object lockFor(String requestId) {
        int h = Math.floorMod(requestId.hashCode(), 256);
        return locks[h];
    }

    private final Object[] locks = createLocks();

    private static Object[] createLocks() {
        Object[] arr = new Object[256];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = new Object();
        }
        return arr;
    }

    public int size() {
        lock.lock();
        try {
            return cache.size();
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            cache.clear();
        } finally {
            lock.unlock();
        }
    }

    /** 调整 TTL/容量（启动配置用）；TTL<=0 时已有条目立即失效。 */
    public void reconfigure(long newTtlMillis, int newCapacity) {
        lock.lock();
        try {
            this.ttlMillis = newTtlMillis;
            this.capacity = newCapacity;
            if (newTtlMillis <= 0) {
                cache.clear();
            }
        } finally {
            lock.unlock();
        }
    }
}
