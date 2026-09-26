package com.github.xingjianluo.zerotrustratelimiterjava.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Observable counters for decision outcomes. Degraded fail-open traffic is counted
 * separately from normal allows so a fail-open path is never silent.
 */
public class DecisionMetrics {

    private final AtomicLong allowed = new AtomicLong();
    private final AtomicLong degradedOpen = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> rejected =
            new ConcurrentHashMap<>();

    public void recordAllowed() {
        allowed.incrementAndGet();
    }

    public void recordDegradedOpen() {
        degradedOpen.incrementAndGet();
    }

    public void recordRejected(String reasonCode) {
        rejected.computeIfAbsent(reasonCode, k -> new AtomicLong()).incrementAndGet();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        result.put("allowed", allowed.get());
        result.put("degradedOpen", degradedOpen.get());
        long totalRejected = 0L;
        for (Map.Entry<String, AtomicLong> entry : rejected.entrySet()) {
            long value = entry.getValue().get();
            result.put("rejected:" + entry.getKey(), value);
            totalRejected += value;
        }
        result.put("rejectedTotal", totalRejected);
        return result;
    }
}
