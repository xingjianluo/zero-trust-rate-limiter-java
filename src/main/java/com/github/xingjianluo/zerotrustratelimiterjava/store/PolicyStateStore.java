package com.github.xingjianluo.zerotrustratelimiterjava.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.xingjianluo.zerotrustratelimiterjava.config.RateLimitProperties;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * 最近一次生效的完整策略配置（含分层 tiers、权重 weights、幂等 TTL、封禁与存储设置）的
 * 文件持久化：每次热更新/首发原子写盘，进程重启时优先从该文件加载，使分层与加权规则、
 * 策略版本号（另存 policy-version.dat）与配额计数命名空间在重启后保持一致。
 *
 * <p>文件路径由 {@code ratelimit.storage.quota-state-file} 同目录派生为 {@code policy.json}；
 * 配额文件为空时不持久化（退化为纯内存，使用 application.properties / 命令行配置）。
 */
@Component
public class PolicyStateStore {

    private static final Logger log = LoggerFactory.getLogger(PolicyStateStore.class);

    private final Path filePath;
    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @org.springframework.beans.factory.annotation.Autowired
    public PolicyStateStore(@Value("${ratelimit.storage.quota-state-file:}") String quotaStateFile) {
        this(quotaStateFile == null || quotaStateFile.isBlank()
                ? null
                : Path.of(quotaStateFile).toAbsolutePath().resolveSibling("policy.json"));
    }

    private PolicyStateStore(Path filePath) {
        this.filePath = filePath;
        if (filePath != null) {
            log.info("Policy state persistence enabled at {}", filePath);
        }
    }

    /** 测试/无持久化场景：通过 {@link #forQuotaFile(String)} 传入空字符串得到纯内存实例。 */

    /** 测试用：显式指定配额文件路径；空字符串表示纯内存。 */
    public static PolicyStateStore forQuotaFile(String quotaStateFile) {
        if (quotaStateFile == null || quotaStateFile.isBlank()) {
            return new PolicyStateStore((Path) null);
        }
        return new PolicyStateStore(
                Path.of(quotaStateFile).toAbsolutePath().resolveSibling("policy.json"));
    }

    /** 启动时加载最近一次持久化的策略配置；不存在或未启用持久化时返回 empty。 */
    public Optional<RateLimitProperties> load() {
        if (filePath == null || !Files.exists(filePath)) {
            return Optional.empty();
        }
        try {
            RateLimitProperties props = mapper.readValue(filePath.toFile(), RateLimitProperties.class);
            log.info("Loaded last applied rate-limit policy from {}", filePath);
            return Optional.of(props);
        } catch (IOException e) {
            log.warn("Failed to load persisted policy from {}; falling back to configured properties",
                    filePath, e);
            return Optional.empty();
        }
    }

    /** 原子持久化最近一次生效的策略配置（临时文件 + 原子 rename）。 */
    public void save(RateLimitProperties properties) {
        if (filePath == null) {
            return;
        }
        try {
            Files.createDirectories(filePath.toAbsolutePath().getParent());
            Path tmp = Files.createTempFile(filePath.toAbsolutePath().getParent(), "policy-", ".tmp");
            mapper.writeValue(tmp.toFile(), properties);
            try {
                Files.move(tmp, filePath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFail) {
                Files.move(tmp, filePath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Failed to persist rate-limit policy to {}", filePath, e);
        }
    }
}
