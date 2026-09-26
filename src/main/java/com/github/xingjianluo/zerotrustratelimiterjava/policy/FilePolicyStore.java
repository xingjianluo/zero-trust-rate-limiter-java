package com.github.xingjianluo.zerotrustratelimiterjava.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RateLimitPolicy;
import com.github.xingjianluo.zerotrustratelimiterjava.store.FaultState;
import com.github.xingjianluo.zerotrustratelimiterjava.store.StoreUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * File-backed {@link PolicyStore} using append-only JSON lines: one serialized
 * {@link RateLimitPolicy} per line. Old versions are physically retained, so the
 * policy a past decision used remains available and is never overwritten.
 */
public class FilePolicyStore implements PolicyStore {

    private static final Logger log = LoggerFactory.getLogger(FilePolicyStore.class);

    private final Path dataFile;
    private final FaultState faultState;
    private final ObjectMapper mapper;
    private final Object writeLock = new Object();

    public FilePolicyStore(Path dataFile, FaultState faultState) {
        this.dataFile = dataFile;
        this.faultState = faultState;
        this.mapper = new ObjectMapper();
    }

    @Override
    public synchronized void save(RateLimitPolicy policy) {
        applyFaults();
        try {
            synchronized (writeLock) {
                Path parent = dataFile.toAbsolutePath().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                String line = mapper.writeValueAsString(policy) + System.lineSeparator();
                Files.writeString(dataFile, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            log.info("Persisted policy version={} effectiveAt={}",
                    policy.version(), policy.effectiveAtMillis());
        } catch (IOException e) {
            throw new StoreUnavailableException(
                    "cannot persist policy file: " + dataFile, e);
        }
    }

    @Override
    public List<RateLimitPolicy> loadAll() {
        applyFaults();
        List<RateLimitPolicy> policies = new ArrayList<>();
        if (!Files.exists(dataFile)) {
            return policies;
        }
        try {
            for (String line : Files.readAllLines(dataFile, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    policies.add(mapper.readValue(line, RateLimitPolicy.class));
                }
            }
        } catch (IOException e) {
            throw new StoreUnavailableException(
                    "cannot read policy file: " + dataFile, e);
        }
        policies.sort(java.util.Comparator.comparingLong(RateLimitPolicy::createdAtMillis));
        return policies;
    }

    private void applyFaults() {
        long delay = faultState.getForcedDelayMillis();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StoreUnavailableException("policy store wait interrupted", e);
            }
        }
        if (faultState.isUnavailable()) {
            throw new StoreUnavailableException("policy store forced unavailable");
        }
    }
}
