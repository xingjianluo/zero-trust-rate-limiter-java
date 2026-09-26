package com.github.xingjianluo.zerotrustratelimiterjava.store;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-process counter storage.
 *
 * <p>Accuracy guarantees:
 * <ul>
 *   <li>{@link #incrementAndGet} is atomic per key, so concurrent requests at a window
 *       boundary never lose or double-count requests.</li>
 *   <li>The key embeds the policy version and the fixed-window start timestamp. When a new
 *       policy becomes effective, or the window rolls over, the next increment creates a
 *       fresh counter; historical counters under the old policy/window are never mutated.</li>
 * </ul>
 * Counters are intentionally not persisted: they are window-scoped ephemeral state.
 * Ban state (which must survive restarts) is stored separately in {@link BanStore}.
 */
public class InMemoryCounterStore implements CounterStore {

    private record Key(String policyVersion, long windowStartMillis,
                       String ruleName, String source) {
    }

    private final ConcurrentHashMap<Key, AtomicLong> counters = new ConcurrentHashMap<>();
    private final FaultState faultState;

    public InMemoryCounterStore(FaultState faultState) {
        this.faultState = faultState;
    }

    @Override
    public long incrementAndGet(String policyVersion, long windowStartMillis,
                                String ruleName, String source) {
        applyFaults();
        return counters.computeIfAbsent(
                new Key(policyVersion, windowStartMillis, ruleName, source),
                k -> new AtomicLong()).incrementAndGet();
    }

    @Override
    public long get(String policyVersion, long windowStartMillis,
                    String ruleName, String source) {
        applyFaults();
        AtomicLong counter = counters.get(
                new Key(policyVersion, windowStartMillis, ruleName, source));
        return counter == null ? 0L : counter.get();
    }

    private void applyFaults() {
        long delay = faultState.getForcedDelayMillis();
        if (delay > 0) {
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StoreUnavailableException("counter store wait interrupted", e);
            }
        }
        if (faultState.isUnavailable()) {
            throw new StoreUnavailableException("counter store forced unavailable");
        }
    }

    /** Test/ops helper: current number of distinct counters. */
    public int size() {
        return counters.size();
    }

    /** Test/ops helper: clear all counters. */
    public void clear() {
        counters.clear();
    }

    /** Immutable view for diagnostics. */
    public Map<Key, Long> snapshot() {
        Map<Key, Long> result = new java.util.TreeMap<>(
                java.util.Comparator.comparing(Key::policyVersion)
                        .thenComparingLong(Key::windowStartMillis)
                        .thenComparing(Key::ruleName)
                        .thenComparing(Key::source));
        counters.forEach((k, v) -> result.put(k, v.get()));
        return result;
    }
}
