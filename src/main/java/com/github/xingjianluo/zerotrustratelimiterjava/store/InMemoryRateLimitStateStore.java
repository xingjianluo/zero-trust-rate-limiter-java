package com.github.xingjianluo.zerotrustratelimiterjava.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.zerotrustratelimiterjava.core.Clock;
import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 {@link ConcurrentHashMap} 的配额计数存储（可选 JSON 文件持久化）。
 *
 * <p>计数键由「策略版本 + 维度 + 维度键 + 窗口起点」构成：窗口切换自然产生新键；
 * 热更新产生新版本命名空间，旧版本计数保留但不再被引用，历史判定不被静默改写。
 *
 * <p>计数单位是消耗量：{@link #consume} 按请求权重原子累加；未配置权重时权重为 1，
 * 与旧行为一致。{@link #snapshot} 只读查询与入口判定读取同一份内存状态。
 *
 * <p>当 {@code ratelimit.storage.quota-state-file} 非空时，每次计数变化后在单线程守护
 * 执行器上异步合并写盘（同一时刻最多一个待执行写任务），进程重启后加载，窗口用量得以延续；
 * 置空时退化为纯内存。测试可调用 {@link #flush()} 等待写盘完成。
 *
 * <p>提供测试用故障注入开关（不可用/延迟），用于验证降级链路。
 */
@Component
public class InMemoryRateLimitStateStore implements RateLimitStateStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRateLimitStateStore.class);

    private record CounterKey(long version, LimitDimension dimension, String dimensionKey,
                              long windowMillis, long windowStart) {
    }

    /** 磁盘文件结构。 */
    private record CounterEntry(long version, String dimension, String dimensionKey,
                                long windowMillis, long windowStart, long used) {
    }

    private record Snapshot(long updatedAt, List<CounterEntry> counters) {
    }

    private final ConcurrentHashMap<CounterKey, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Path filePath;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService persistExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ratelimit-quota-persist");
                t.setDaemon(true);
                return t;
            });
    private final AtomicBoolean persistQueued = new AtomicBoolean(false);
    private final Object persistLock = new Object();
    private volatile boolean faultUnavailable;
    private volatile long faultDelayMillis;
    private final AtomicLong opsCount = new AtomicLong();

    @org.springframework.beans.factory.annotation.Autowired
    public InMemoryRateLimitStateStore(
            Clock clock,
            @Value("${ratelimit.storage.quota-state-file:}") String filePath) {
        this.clock = clock;
        this.filePath = (filePath == null || filePath.isBlank()) ? null : Path.of(filePath);
        load();
    }

    /** 纯内存便捷构造（无持久化文件，使用系统时钟），供测试与无文件场景使用。 */
    public InMemoryRateLimitStateStore() {
        this(new com.github.xingjianluo.zerotrustratelimiterjava.core.SystemClock(), "");
    }

    private void load() {
        if (filePath == null || !Files.exists(filePath)) {
            return;
        }
        try {
            Snapshot snapshot = mapper.readValue(filePath.toFile(), new TypeReference<Snapshot>() {
            });
            if (snapshot != null && snapshot.counters() != null) {
                for (CounterEntry e : snapshot.counters()) {
                    CounterKey key = new CounterKey(e.version(),
                            LimitDimension.valueOf(e.dimension()), e.dimensionKey(),
                            e.windowMillis(), e.windowStart());
                    counters.put(key, new AtomicLong(e.used()));
                }
            }
            log.info("Loaded {} quota counters from {}", counters.size(), filePath);
        } catch (IOException | IllegalArgumentException e) {
            throw new StoreException(StoreException.Kind.STORE_UNAVAILABLE,
                    "failed to load quota state file: " + filePath, e);
        }
    }

    @Override
    public long tryConsume(long version, LimitDimension dimension, String dimensionKey,
                           long windowMillis, long windowStart, long weight, long limit) {
        if (faultUnavailable) {
            throw new StoreException(StoreException.Kind.STORE_UNAVAILABLE,
                    "injected fault: rate-limit state store unavailable", null);
        }
        sleepFaultDelay();
        CounterKey key = countersKey(version, dimension, dimensionKey, windowMillis, windowStart);
        AtomicLong counter = counters.computeIfAbsent(key, k -> new AtomicLong());
        long reserved = reserve(counter, weight, limit);
        if (reserved >= 0) {
            schedulePersist();
        }
        occasionallyPrune(version, windowStart, windowMillis);
        return reserved;
    }

    /**
     * 保守惰性裁剪：每 256 次预留执行一次，只删除同一版本中窗口起点早于
     * {@code currentWindowStart - 2 * windowMillis} 的计数。分层配额下不同层窗口长度不同，
     * 该阈值保证短/长窗口各自已彻底翻过的计数才会被回收，不影响任何在用窗口。
     */
    private void occasionallyPrune(long version, long currentWindowStart, long windowMillis) {
        long count = opsCount.incrementAndGet();
        if (count % 256 != 0) {
            return;
        }
        long threshold = currentWindowStart - 2L * windowMillis;
        counters.keySet().removeIf(k ->
                k.version() == version && k.windowMillis() == windowMillis
                        && k.windowStart() < threshold);
    }

    @Override
    public void refund(long version, LimitDimension dimension, String dimensionKey,
                       long windowMillis, long windowStart, long weight) {
        if (faultUnavailable) {
            throw new StoreException(StoreException.Kind.STORE_UNAVAILABLE,
                    "injected fault: rate-limit state store unavailable", null);
        }
        sleepFaultDelay();
        CounterKey key = countersKey(version, dimension, dimensionKey, windowMillis, windowStart);
        AtomicLong counter = counters.get(key);
        if (counter != null) {
            counter.addAndGet(-weight);
            schedulePersist();
        }
    }

    /** 计数键：版本 + 维度 + 维度键 + 窗口长度 + 窗口起点；窗口长度区分短/长不同层。 */
    private CounterKey countersKey(long version, LimitDimension dimension, String dimensionKey,
                                   long windowMillis, long windowStart) {
        return new CounterKey(version, dimension, dimensionKey, windowMillis, windowStart);
    }

    /**
     * CAS 预留：仅当 {@code used + weight <= limit} 时累加并返回新值，否则返回 -1 且不改动。
     */
    private long reserve(AtomicLong counter, long weight, long limit) {
        while (true) {
            long current = counter.get();
            if (current + weight > limit) {
                return -1L;
            }
            if (counter.compareAndSet(current, current + weight)) {
                return current + weight;
            }
        }
    }

    /** 读取某键当前消耗量（不存在为 0），供拒绝结论报告“当前已用量”。 */
    public long currentUsed(long version, LimitDimension dimension, String dimensionKey,
                            long windowMillis, long windowStart) {
        AtomicLong counter = counters.get(
                countersKey(version, dimension, dimensionKey, windowMillis, windowStart));
        return counter == null ? 0L : counter.get();
    }

    @Override
    public List<CounterSnapshot> snapshot(long version, LimitDimension dimension, String dimensionKey,
                                          long windowMillis, List<Long> windowStarts) {
        if (faultUnavailable) {
            throw new StoreException(StoreException.Kind.STORE_UNAVAILABLE,
                    "injected fault: rate-limit state store unavailable", null);
        }
        sleepFaultDelay();
        List<CounterSnapshot> out = new ArrayList<>();
        for (Long windowStart : windowStarts) {
            AtomicLong counter = counters.get(
                    countersKey(version, dimension, dimensionKey, windowMillis, windowStart));
            if (counter != null) {
                out.add(new CounterSnapshot(version, dimension, dimensionKey,
                        windowMillis, windowStart, counter.get()));
            }
        }
        return out;
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

    // ---- 异步合并持久化 ----

    private void schedulePersist() {
        if (filePath == null) {
            return;
        }
        if (persistQueued.compareAndSet(false, true)) {
            persistExecutor.submit(() -> {
                persistQueued.set(false);
                writeSnapshot();
            });
        }
    }

    /** 阻塞等待所有已调度的写盘完成（测试在模拟重启前调用）。 */
    public void flush() {
        if (filePath == null) {
            return;
        }
        try {
            Future<?> done = persistExecutor.submit(() -> {
            });
            done.get();
        } catch (Exception e) {
            Thread.currentThread().interrupt();
            throw new StoreException(StoreException.Kind.STORE_UNAVAILABLE,
                    "failed while waiting for quota state flush", e);
        }
    }

    private void writeSnapshot() {
        if (filePath == null) {
            return;
        }
        synchronized (persistLock) {
            try {
                Path parent = filePath.toAbsolutePath().getParent();
                Files.createDirectories(parent);
                List<CounterEntry> entries = new ArrayList<>();
                for (Map.Entry<CounterKey, AtomicLong> e : counters.entrySet()) {
                    CounterKey k = e.getKey();
                    entries.add(new CounterEntry(k.version(), k.dimension().name(),
                            k.dimensionKey(), k.windowMillis(), k.windowStart(), e.getValue().get()));
                }
                Path tmp = Files.createTempFile(parent, "quota-state-", ".tmp");
                mapper.writeValue(tmp.toFile(), new Snapshot(clock.nowMillis(), entries));
                try {
                    Files.move(tmp, filePath,
                            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException atomicFail) {
                    Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                log.warn("Failed to persist quota state file: {}", filePath, e);
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
        flush();
        counters.clear();
        faultUnavailable = false;
        faultDelayMillis = 0L;
    }
}
