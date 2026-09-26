package com.github.xingjianluo.zerotrustratelimiterjava.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.zerotrustratelimiterjava.core.Clock;
import com.github.xingjianluo.zerotrustratelimiterjava.model.BanRecord;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * 内存 + JSON 文件持久化的封禁存储：启动时加载，每次变更后原子写盘，
 * 使进程重启后封禁/解封语义保持一致。{@code ban-state-file} 为空时退化为纯内存。
 *
 * <p>读-改-写通过 {@link ConcurrentHashMap#compute} 原子完成；写盘在独立锁内对全量快照
 * 序列化为临时文件后原子 rename，避免读到半写文件。
 *
 * <p>提供测试用故障注入（写盘/访问失败、延迟）。
 */
@Component
public class FileBanStateStore implements BanStateStore {

    private static final Logger log = LoggerFactory.getLogger(FileBanStateStore.class);

    /** 磁盘文件结构。 */
    private record Snapshot(long updatedAt, List<BanRecord> records) {
    }

    private final ConcurrentHashMap<String, BanRecord> records = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();
    private final Object persistLock = new Object();
    private final Clock clock;
    private final Path filePath;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile boolean faultUnavailable;
    private volatile long faultDelayMillis;

    public FileBanStateStore(Clock clock,
                             @Value("${ratelimit.storage.ban-state-file:}") String filePath) {
        this.clock = clock;
        this.filePath = (filePath == null || filePath.isBlank()) ? null : Path.of(filePath);
        warmUpSerialization();
        load();
    }

    /**
     * 预热 Jackson 与临时文件通道，避免首个真实请求把冷启动耗时计入存储延迟而误触发超时降级。
     */
    private void warmUpSerialization() {
        if (filePath == null) {
            return;
        }
        try {
            Path parent = filePath.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "ban-state-warmup-", ".tmp");
            mapper.writeValue(tmp.toFile(), new Snapshot(clock.nowMillis(), List.of()));
            Files.deleteIfExists(tmp);
        } catch (IOException e) {
            throw new StoreException(StoreException.Kind.STORE_UNAVAILABLE,
                    "failed to warm up ban state file location: " + filePath, e);
        }
    }

    private void load() {
        if (filePath == null || !Files.exists(filePath)) {
            return;
        }
        try {
            Snapshot snapshot = mapper.readValue(filePath.toFile(), new TypeReference<Snapshot>() {
            });
            if (snapshot != null && snapshot.records() != null) {
                for (BanRecord r : snapshot.records()) {
                    records.put(r.key(), r);
                }
            }
            log.info("Loaded {} ban records from {}", records.size(), filePath);
        } catch (IOException e) {
            throw new StoreException(StoreException.Kind.STORE_UNAVAILABLE,
                    "failed to load ban state file: " + filePath, e);
        }
    }

    @Override
    public BanRecord compute(String key, UnaryOperator<BanRecord> mutation) {
        applyFaults();
        // 按键分段加锁，保证读-改-写原子；ConcurrentHashMap 不允许在 compute 中为新键返回 null，
        // 因此这里显式管理 put/remove。
        Object lock = lockFor(key);
        final BanRecord updated;
        final boolean changed;
        synchronized (lock) {
            BanRecord before = records.get(key);
            BanRecord after = mutation.apply(before == null ? BanRecord.empty(key) : before);
            if (after == null) {
                if (before != null) {
                    records.remove(key);
                }
                updated = BanRecord.empty(key);
                changed = before != null;
            } else {
                records.put(key, after);
                updated = after;
                changed = !after.equals(before);
            }
        }
        if (changed) {
            persist();
        }
        return updated;
    }

    /**
     * 分段锁：同一来源串行化，不同来源可并行。锁表本身在首次见到新键时通过
     * {@link ConcurrentHashMap#computeIfAbsent} 原子创建。
     */
    private Object lockFor(String key) {
        return locks.computeIfAbsent(key, k -> new Object());
    }

    @Override
    public Optional<BanRecord> find(String key) {
        return Optional.ofNullable(records.get(key));
    }

    @Override
    public List<BanRecord> findAll() {
        return new ArrayList<>(records.values());
    }

    private void persist() {
        if (filePath == null) {
            return;
        }
        synchronized (persistLock) {
            try {
                Path parent = filePath.toAbsolutePath().getParent();
                Files.createDirectories(parent);
                Path tmp = Files.createTempFile(parent, "ban-state-", ".tmp");
                Snapshot snapshot = new Snapshot(clock.nowMillis(), new ArrayList<>(records.values()));
                mapper.writeValue(tmp.toFile(), snapshot);
                try {
                    Files.move(tmp, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException atomicFail) {
                    Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new StoreException(StoreException.Kind.STORE_UNAVAILABLE,
                        "failed to persist ban state file: " + filePath, e);
            }
        }
    }

    private void applyFaults() {
        if (faultUnavailable) {
            throw new StoreException(StoreException.Kind.STORE_UNAVAILABLE,
                    "injected fault: ban state store unavailable", null);
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

    /** 注入持久化不可用故障。 */
    public void setFaultUnavailable(boolean fault) {
        this.faultUnavailable = fault;
    }

    /** 注入每次调用的额外延迟（毫秒），用于触发超时降级。 */
    public void setFaultDelayMillis(long delayMillis) {
        this.faultDelayMillis = delayMillis;
    }

    /** 仅测试用：清空全部记录与故障注入（不强制写盘）。 */
    public void reset() {
        synchronized (persistLock) {
            records.clear();
            locks.clear();
            faultUnavailable = false;
            faultDelayMillis = 0L;
        }
    }
}
