package com.github.xingjianluo.zerotrustratelimiterjava.store;

import com.github.xingjianluo.zerotrustratelimiterjava.core.Clock;
import com.github.xingjianluo.zerotrustratelimiterjava.core.SystemClock;
import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;
import com.github.xingjianluo.zerotrustratelimiterjava.model.QuotaLayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 加权分层计数器的公共实现：分段锁保证读-改-写原子，按“窗口起点 + 窗口长度”判定过期，
 * 每次变更后回调 {@link #persist(List)} 由子类决定持久化方式。
 *
 * <p>计数键为「版本 + 分层名 + 维度 + 维度键 + 窗口起点」：
 * 不同层即便窗口长度不同也互不干扰；热更新产生新版本命名空间，历史用量不被改写。
 */
public abstract class AbstractRateLimitStateStore implements RateLimitStateStore {

    record CounterKey(long version, String layer, LimitDimension dimension,
                      String dimensionKey, long windowStart) {
    }

    private final ConcurrentHashMap<CounterKey, WindowCounter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final Clock clock;
    private volatile boolean faultUnavailable;
    private volatile long faultDelayMillis;

    protected AbstractRateLimitStateStore(Clock clock) {
        this.clock = clock == null ? new SystemClock() : clock;
    }

    /** 启动加载历史计数（子类构造时调用）；已过期的窗口直接丢弃。 */
    protected void loadAll(List<WindowCounter> loaded) {
        long now = clock.nowMillis();
        if (loaded != null) {
            for (WindowCounter c : loaded) {
                if (c != null && c.windowStart() + c.windowMillis() > now) {
                    counters.put(keyOf(c), c);
                }
            }
        }
    }

    /** 变更后的全量持久化回调；纯内存实现可为空操作。 */
    protected abstract void persist(List<WindowCounter> snapshot);

    @Override
    public long incrementAndGet(long version, LimitDimension dimension, String dimensionKey, long windowStart) {
        // 旧接口（无分层/权重）：独立命名空间，权重视为 1；新判定链路不再使用本方法。
        applyFaults();
        String layerName = "__legacy__:" + dimension.name();
        CounterKey key = new CounterKey(version, layerName, dimension, dimensionKey, windowStart);
        long value = mutate(key, 1L, Long.MAX_VALUE / 2);
        pruneExpired();
        return value;
    }

    @Override
    public long consumeAndGet(long version, QuotaLayer layer, String dimensionKey,
                              long windowStart, long weight) {
        applyFaults();
        CounterKey key = new CounterKey(version, layer.name(), layer.dimension(), dimensionKey, windowStart);
        long consumed = mutate(key, weight, layer.windowMillis());
        pruneExpired();
        return consumed;
    }

    private long mutate(CounterKey key, long weight, long windowMillis) {
        Object lock = locks.computeIfAbsent(key.layer() + "|" + key.dimension() + "|" + key.dimensionKey(),
                k -> new Object());
        synchronized (lock) {
            WindowCounter cur = counters.get(key);
            long newConsumed = (cur == null ? 0L : cur.consumed()) + weight;
            WindowCounter next = new WindowCounter(key.version(), key.layer(), key.dimension(),
                    key.dimensionKey(), key.windowStart(), windowMillis, newConsumed);
            counters.put(key, next);
            persist(snapshot());
            return newConsumed;
        }
    }

    @Override
    public Optional<WindowCounter> find(long version, String layer, LimitDimension dimension,
                                        String dimensionKey, long windowStart) {
        return Optional.ofNullable(counters.get(
                new CounterKey(version, layer, dimension, dimensionKey, windowStart)));
    }

    @Override
    public List<WindowCounter> findActive(long nowMillis) {
        List<WindowCounter> out = new ArrayList<>();
        for (WindowCounter c : counters.values()) {
            if (c.windowStart() + c.windowMillis() > nowMillis) {
                out.add(c);
            }
        }
        return out;
    }

    @Override
    public void pruneOlderThan(long minWindowStart) {
        boolean removed = false;
        for (CounterKey key : counters.keySet()) {
            WindowCounter c = counters.get(key);
            if (c != null && c.windowStart() < minWindowStart
                    && c.windowStart() + c.windowMillis() <= clock.nowMillis()) {
                counters.remove(key);
                removed = true;
            }
        }
        if (removed) {
            persist(snapshot());
        }
    }

    /** 删除窗口已结束的计数（不同窗口长度各自判断，长窗口计数不会被短窗口切换误删）。 */
    private void pruneExpired() {
        long now = clock.nowMillis();
        boolean removed = false;
        for (CounterKey key : counters.keySet()) {
            WindowCounter c = counters.get(key);
            if (c != null && !key.layer().startsWith("__legacy__")
                    && c.windowStart() + c.windowMillis() <= now) {
                counters.remove(key);
                removed = true;
            }
        }
        if (removed) {
            persist(snapshot());
        }
    }

    private List<WindowCounter> snapshot() {
        return new ArrayList<>(counters.values());
    }

    private CounterKey keyOf(WindowCounter c) {
        return new CounterKey(c.version(), c.layer(), c.dimension(), c.dimensionKey(), c.windowStart());
    }

    private void applyFaults() {
        if (faultUnavailable) {
            throw new StoreException(StoreException.Kind.STORE_UNAVAILABLE,
                    "injected fault: rate-limit state store unavailable", null);
        }
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

    public void setFaultUnavailable(boolean fault) {
        this.faultUnavailable = fault;
    }

    public void setFaultDelayMillis(long delayMillis) {
        this.faultDelayMillis = delayMillis;
    }

    public int size() {
        return counters.size();
    }

    public void reset() {
        counters.clear();
        locks.clear();
        faultUnavailable = false;
        faultDelayMillis = 0L;
    }
}
