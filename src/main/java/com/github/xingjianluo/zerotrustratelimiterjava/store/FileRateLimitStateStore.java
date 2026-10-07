package com.github.xingjianluo.zerotrustratelimiterjava.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.zerotrustratelimiterjava.core.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 内存 + JSON 文件持久化的加权分层计数存储：启动时加载未过期窗口，每次变更原子写盘，
 * 使进程重启后当前窗口用量与判定语义延续；{@code rate-state-file} 为空时退化为纯内存。
 *
 * <p>写盘在独立锁内对全量快照序列化为临时文件后原子 rename，避免读到半写文件。
 * 已过窗口的计数在加载与运行期都会被丢弃，因此文件不会无界增长。
 *
 * <p>注意：这是本地演示用途的单进程持久化；计数器对同一维度键的读-改-写在
 * {@link AbstractRateLimitStateStore} 内分段加锁，并发消耗结果确定。
 */
public class FileRateLimitStateStore extends AbstractRateLimitStateStore {

    private static final Logger log = LoggerFactory.getLogger(FileRateLimitStateStore.class);

    private record Snapshot(long updatedAt, List<WindowCounter> counters) {
    }

    private final Path filePath;
    private final Object persistLock = new Object();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;

    public FileRateLimitStateStore(Clock clock, String filePath) {
        super(clock);
        this.clock = clock;
        this.filePath = (filePath == null || filePath.isBlank()) ? null : Path.of(filePath);
        warmUp();
        load();
    }

    private void warmUp() {
        if (filePath == null) {
            return;
        }
        try {
            Path parent = filePath.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "rate-state-warmup-", ".tmp");
            mapper.writeValue(tmp.toFile(), new Snapshot(clock.nowMillis(), List.of()));
            Files.deleteIfExists(tmp);
        } catch (IOException e) {
            throw new StoreException(StoreException.Kind.STORE_UNAVAILABLE,
                    "failed to warm up rate state file location: " + filePath, e);
        }
    }

    private void load() {
        if (filePath == null || !Files.exists(filePath)) {
            return;
        }
        try {
            Snapshot snapshot = mapper.readValue(filePath.toFile(), new TypeReference<Snapshot>() {
            });
            if (snapshot != null && snapshot.counters() != null) {
                loadAll(snapshot.counters());
                log.info("Loaded rate counters from {} (file entries={})",
                        filePath, snapshot.counters().size());
            }
        } catch (IOException e) {
            throw new StoreException(StoreException.Kind.STORE_UNAVAILABLE,
                    "failed to load rate state file: " + filePath, e);
        }
    }

    @Override
    protected void persist(List<WindowCounter> snapshot) {
        if (filePath == null) {
            return;
        }
        synchronized (persistLock) {
            try {
                Path parent = filePath.toAbsolutePath().getParent();
                Files.createDirectories(parent);
                Path tmp = Files.createTempFile(parent, "rate-state-", ".tmp");
                mapper.writeValue(tmp.toFile(), new Snapshot(clock.nowMillis(), snapshot));
                try {
                    Files.move(tmp, filePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException atomicFail) {
                    Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new StoreException(StoreException.Kind.STORE_UNAVAILABLE,
                        "failed to persist rate state file: " + filePath, e);
            }
        }
    }
}
