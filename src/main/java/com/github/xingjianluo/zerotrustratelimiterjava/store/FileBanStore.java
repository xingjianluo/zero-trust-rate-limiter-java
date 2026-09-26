package com.github.xingjianluo.zerotrustratelimiterjava.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.xingjianluo.zerotrustratelimiterjava.model.BanState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * File-backed {@link BanStore}.
 *
 * <p>The whole state map is persisted as JSON on every mutation using
 * write-temp-then-atomic-rename, so a crash never leaves a half-written state file.
 * On construction the snapshot is loaded from disk, giving ban state consistent
 * semantics across process restarts.
 */
public class FileBanStore implements BanStore {

    private static final Logger log = LoggerFactory.getLogger(FileBanStore.class);

    private final Path dataFile;
    private final FaultState faultState;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, BanState> states = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    public FileBanStore(Path dataFile, FaultState faultState) {
        this.dataFile = dataFile;
        this.faultState = faultState;
        this.mapper = new ObjectMapper();
        load();
    }

    private void load() {
        if (!Files.exists(dataFile)) {
            log.info("Ban state file {} does not exist yet; starting empty", dataFile);
            return;
        }
        try {
            BanStateMap loaded = mapper.readValue(dataFile.toFile(), BanStateMap.class);
            if (loaded.states != null) {
                states.putAll(loaded.states);
            }
            log.info("Loaded {} ban state(s) from {}", states.size(), dataFile);
        } catch (IOException e) {
            throw new StoreUnavailableException(
                    "cannot read ban state file: " + dataFile, e);
        }
    }

    private void persist() {
        applyFaults();
        BanStateMap payload;
        synchronized (writeLock) {
            payload = new BanStateMap(new TreeMap<>(states));
            Path parent = dataFile.toAbsolutePath().getParent();
            try {
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                Path tmp = Files.createTempFile(parent, "bans-", ".tmp");
                mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), payload);
                Files.move(tmp, dataFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                throw new StoreUnavailableException(
                        "cannot persist ban state file: " + dataFile, e);
            }
        }
    }

    private void applyFaults() {
        long delay = faultState.getForcedDelayMillis();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StoreUnavailableException("ban store wait interrupted", e);
            }
        }
        if (faultState.isUnavailable()) {
            throw new StoreUnavailableException("ban store forced unavailable");
        }
    }

    @Override
    public BanState get(String source) {
        // Read access is local and never fault-injected: enforcement must keep working
        // even when the backing file cannot be written to.
        return states.get(source);
    }

    @Override
    public void put(BanState state) {
        states.put(state.source(), state);
        persist();
        log.info("Ban state persisted for source={}: violations={}, level={}, banUntil={}",
                state.source(), state.violations(), state.banLevel(),
                state.banUntilMillis());
    }

    @Override
    public void remove(String source) {
        BanState previous = states.remove(source);
        if (previous != null) {
            persist();
            log.info("Ban state removed for source={} (manual unban)", source);
        }
    }

    @Override
    public Map<String, BanState> snapshot() {
        return Map.copyOf(states);
    }

    /** Jackson wrapper for a stable JSON root. */
    private record BanStateMap(Map<String, BanState> states) {
    }
}
