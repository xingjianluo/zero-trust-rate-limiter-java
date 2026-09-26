package com.github.xingjianluo.zerotrustratelimiterjava.policy;

import com.github.xingjianluo.zerotrustratelimiterjava.core.TimeSource;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RateLimitPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the immutable history of policies and selects the active policy for an instant.
 *
 * <p>Boundary semantics (deterministic):
 * <ul>
 *   <li>A version is active at instant {@code t} iff {@code effectiveAtMillis <= t}.
 *       The boundary instant itself belongs to the new policy.</li>
 *   <li>Among effective versions, the one with the largest {@code effectiveAtMillis}
 *       wins (creation order as tie-break).</li>
 *   <li>Old versions and their counters are retained; switching versions never mutates
 *       decisions made under a previous version.</li>
 * </ul>
 */
public class PolicyManager {

    private static final Logger log = LoggerFactory.getLogger(PolicyManager.class);

    private final PolicyStore store;
    private final TimeSource timeSource;
    private final CopyOnWriteArrayList<RateLimitPolicy> history = new CopyOnWriteArrayList<>();
    private volatile RateLimitPolicy defaultPolicy;

    public PolicyManager(PolicyStore store, TimeSource timeSource) {
        this.store = store;
        this.timeSource = timeSource;
        reload();
    }

    /** Persist and activate the seeded default only when no version exists yet. */
    public void seedIfEmpty(RateLimitPolicy policy) {
        if (history.isEmpty()) {
            this.defaultPolicy = policy;
            store.save(policy);
            history.add(policy);
            log.info("Seeded default policy version={}", policy.version());
        } else {
            this.defaultPolicy = history.get(0);
        }
    }

    /**
     * Publish a new immutable version. {@code policy.effectiveAtMillis()} must be
     * aligned to a chosen window boundary by the caller when a clean new window is
     * required; the boundary itself is inclusive.
     */
    public void publish(RateLimitPolicy policy) {
        store.save(policy);
        List<RateLimitPolicy> merged = new ArrayList<>(history);
        merged.add(policy);
        merged.sort(Comparator.comparingLong(RateLimitPolicy::effectiveAtMillis)
                .thenComparingLong(RateLimitPolicy::createdAtMillis));
        history.clear();
        history.addAll(merged);
        log.info("Policy published version={} effectiveAt={}; activeAt(now)={}",
                policy.version(), policy.effectiveAtMillis(),
                activeAt(timeSource.currentTimeMillis()).version());
    }

    /** Re-read all versions from the persistent store (external/manual hot update). */
    public void reload() {
        List<RateLimitPolicy> loaded = store.loadAll();
        history.clear();
        history.addAll(loaded);
        if (!loaded.isEmpty()) {
            defaultPolicy = loaded.stream()
                    .min(Comparator.comparingLong(RateLimitPolicy::createdAtMillis))
                    .orElse(null);
            log.info("Reloaded {} policy version(s); active version={}",
                    loaded.size(), activeAt(timeSource.currentTimeMillis()).version());
        }
    }

    /** Active policy at the given instant; never null once a default was seeded. */
    public RateLimitPolicy activeAt(long nowMillis) {
        RateLimitPolicy selected = null;
        for (RateLimitPolicy policy : history) {
            if (policy.effectiveAtMillis() <= nowMillis
                    && (selected == null
                    || policy.effectiveAtMillis() > selected.effectiveAtMillis()
                    || (policy.effectiveAtMillis() == selected.effectiveAtMillis()
                    && policy.createdAtMillis() >= selected.createdAtMillis()))) {
                selected = policy;
            }
        }
        if (selected == null) {
            // Nothing effective yet (all versions scheduled in the future): fall back to
            // the oldest known version rather than failing open with no rules.
            selected = defaultPolicy != null
                    ? defaultPolicy
                    : history.stream().min(Comparator.comparingLong(
                            RateLimitPolicy::createdAtMillis)).orElseThrow();
        }
        return selected;
    }

    public List<RateLimitPolicy> history() {
        return List.copyOf(history);
    }
}
