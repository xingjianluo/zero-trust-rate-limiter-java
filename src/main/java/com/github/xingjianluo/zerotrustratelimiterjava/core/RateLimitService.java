package com.github.xingjianluo.zerotrustratelimiterjava.core;

import com.github.xingjianluo.zerotrustratelimiterjava.model.BanState;
import com.github.xingjianluo.zerotrustratelimiterjava.model.FailStrategy;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RateLimitDecision;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RateLimitPolicy;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RateLimitRule;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RejectReason;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestEntry;
import com.github.xingjianluo.zerotrustratelimiterjava.policy.PolicyManager;
import com.github.xingjianluo.zerotrustratelimiterjava.store.BanStore;
import com.github.xingjianluo.zerotrustratelimiterjava.store.CounterStore;
import com.github.xingjianluo.zerotrustratelimiterjava.store.StoreException;
import com.github.xingjianluo.zerotrustratelimiterjava.store.StoreTimeoutException;
import com.github.xingjianluo.zerotrustratelimiterjava.store.StoreUnavailableException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Entry-point decision engine.
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Exactly one {@link RateLimitDecision} per {@code requestId} within the dedup TTL:
 *       retries/duplicates receive the identical verdict, never contradictory results.</li>
 *   <li>Banned sources are rejected up-front without touching counters.</li>
 *   <li>Counter failures are classified as unavailable vs latency and handled by the
 *       configured OPEN/CLOSED strategy; OPEN is always flagged degraded and metered.</li>
 *   <li>Every decision carries its policy version, window, counters and reason and is
 *       logged with the request id and inputs.</li>
 * </ul>
 */
public class RateLimitService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitService.class);
    private static final int SWEEP_THRESHOLD = 1024;

    private final CounterStore counterStore;
    private final BanStore banStore;
    private final PolicyManager policyManager;
    private final TimeSource timeSource;
    private final FailStrategy failStrategy;
    private final long storeTimeoutMillis;
    private final long dedupTtlMillis;
    private final DecisionMetrics metrics;
    private final DecisionLog decisionLog;
    private final ExecutorService storeExecutors = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "store-call");
        t.setDaemon(true);
        return t;
    });

    private record Cached(RateLimitDecision decision, long expiresAt) {
    }

    private final ConcurrentHashMap<String, Cached> dedup = new ConcurrentHashMap<>();

    public RateLimitService(CounterStore counterStore, BanStore banStore,
                            PolicyManager policyManager, TimeSource timeSource,
                            FailStrategy failStrategy, long storeTimeoutMillis,
                            long dedupTtlMillis, DecisionMetrics metrics,
                            DecisionLog decisionLog) {
        this.counterStore = counterStore;
        this.banStore = banStore;
        this.policyManager = policyManager;
        this.timeSource = timeSource;
        this.failStrategy = failStrategy;
        this.storeTimeoutMillis = storeTimeoutMillis;
        this.dedupTtlMillis = dedupTtlMillis;
        this.metrics = metrics;
        this.decisionLog = decisionLog;
    }

    /** Evaluate one entry request. */
    public RateLimitDecision decide(RequestEntry request) {
        long now = timeSource.currentTimeMillis();
        sweepExpired(now);
        Cached cached = dedup.get(request.requestId());
        if (cached != null && cached.expiresAt() > now) {
            // Replay the recorded verdict: do not count metrics again, and never produce
            // a contradictory conclusion for the same request id.
            log.info("requestId={} replay-cached decision (no contradiction): {}",
                    request.requestId(), cached.decision());
            return cached.decision();
        }

        RateLimitDecision decision = evaluate(request, now);
        dedup.put(request.requestId(),
                new Cached(decision, now + dedupTtlMillis));
        recordMetrics(decision);
        return decision;
    }

    private RateLimitDecision evaluate(RequestEntry request, long now) {
        RateLimitPolicy policy = policyManager.activeAt(now);
        List<RateLimitRule> matching = matchingRules(policy, request.path());

        if (matching.isEmpty()) {
            return finish(allow(request, policy, null, 0L, 0L, 0L, now, null));
        }

        RateLimitRule firstRule = matching.get(0);
        String banSource = request.dimensionValue(firstRule.dimension());

        // 1) Ban enforcement (local read; recompute lazy recovery deterministically).
        BanState state = BanEvaluator.expireIfRecovered(
                banStore.get(banSource), policy, now);
        if (BanEvaluator.isBanned(state, now)) {
            RateLimitDecision banned = new RateLimitDecision(
                    request.requestId(), false, false,
                    RejectReason.BANNED.name(),
                    "source is banned until banUntil; level=" + state.banLevel(),
                    policy.version(), null, 0L, 0L, 0L,
                    banSource, state.banLevel(), state.banUntilMillis(), now);
            log.info("requestId={} inputs={} policy={} => REJECT BANNED source={} level={} banUntil={}",
                    request.requestId(), inputs(request), policy.version(), banSource,
                    state.banLevel(), state.banUntilMillis());
            return finish(banned);
        }

        // 2) Rule-by-rule fixed-window counting. First exceeded rule wins (stable order).
        RateLimitRule lastRule = firstRule;
        long lastWindowStart = Windows.windowStart(now, firstRule.windowSeconds());
        long lastCount = 0L;
        for (RateLimitRule rule : matching) {
            String source = request.dimensionValue(rule.dimension());
            long windowStart = Windows.windowStart(now, rule.windowSeconds());
            long count;
            try {
                count = incrementWithTimeout(policy.version(), windowStart, rule, source);
            } catch (StoreTimeoutException e) {
                return finish(storeFailure(request, policy, rule, windowStart, source,
                        now, RejectReason.FAIL_CLOSED_TIMEOUT,
                        "counter store latency exceeded threshold " + storeTimeoutMillis
                                + "ms", e));
            } catch (StoreException e) {
                return finish(storeFailure(request, policy, rule, windowStart, source,
                        now, RejectReason.FAIL_CLOSED_UNAVAILABLE,
                        "counter store unavailable: " + e.getMessage(), e));
            }
            lastRule = rule;
            lastWindowStart = windowStart;
            lastCount = count;

            if (count > rule.maxRequests()) {
                BanState escalated = escalate(banSource, policy, now);
                RateLimitDecision limited = new RateLimitDecision(
                        request.requestId(), false, false,
                        RejectReason.RATE_LIMITED.name(),
                        "observed " + count + " > limit " + rule.maxRequests()
                                + " in window starting " + windowStart
                                + " (rule=" + rule.name() + ")"
                                + (escalated != null && BanEvaluator.isBanned(escalated, now)
                                ? "; ban escalated to level " + escalated.banLevel() : ""),
                        policy.version(), rule.name(), windowStart, count,
                        rule.maxRequests(), source,
                        escalated == null ? 0 : escalated.banLevel(),
                        escalated == null ? 0L : escalated.banUntilMillis(), now);
                log.warn("requestId={} inputs={} policy={} rule={} windowStart={} count={}/{} => REJECT RATE_LIMITED source={}",
                        request.requestId(), inputs(request), policy.version(), rule.name(),
                        windowStart, count, rule.maxRequests(), source);
                return finish(limited);
            }
        }

        RateLimitDecision allowed = allow(request, policy, lastRule.name(),
                lastWindowStart, lastRule.maxRequests(), lastCount, now, banSource);
        return finish(allowed);
    }

    private RateLimitDecision storeFailure(RequestEntry request, RateLimitPolicy policy,
                                           RateLimitRule rule, long windowStart,
                                           String source, long now,
                                           RejectReason reason, String detail,
                                           Exception error) {
        boolean degraded = failStrategy == FailStrategy.OPEN;
        log.error("requestId={} inputs={} policy={} rule={} store-fail reason={} strategy={} degraded={} detail={}",
                request.requestId(), inputs(request), policy.version(), rule.name(),
                reason, failStrategy, degraded, detail, error);
        if (degraded) {
            return new RateLimitDecision(
                    request.requestId(), true, true, "DEGRADED_OPEN",
                    "allowed under fail-open: " + detail,
                    policy.version(), rule.name(), windowStart, 0L,
                    rule.maxRequests(), source, 0, 0L, now);
        }
        return new RateLimitDecision(
                request.requestId(), false, false, reason.name(), detail,
                policy.version(), rule.name(), windowStart, 0L, rule.maxRequests(),
                source, 0, 0L, now);
    }

    private BanState escalate(String source, RateLimitPolicy policy, long now) {
        try {
            BanState updated = BanEvaluator.registerViolation(
                    banStore.get(source), policy, source, now);
            persistBan(updated);
            log.warn("Ban state updated source={} violations={} level={} banUntil={}",
                    source, updated.violations(), updated.banLevel(),
                    updated.banUntilMillis());
            return updated;
        } catch (StoreException e) {
            // Escalation persistence must not turn a definite rate-limit rejection into
            // an allow. The request stays RATE_LIMITED; the error is observable here.
            log.error("Ban escalation could not be persisted for source={}: {}",
                    source, e.toString(), e);
            return null;
        }
    }

    /** Manually lift a ban; effective immediately for subsequent requests. */
    public void unban(String source) {
        banStore.remove(source);
        log.info("Manual unban source={} effective immediately", source);
    }

    private long incrementWithTimeout(String version, long windowStart,
                                      RateLimitRule rule, String source) {
        Future<Long> future = storeExecutors.submit(() ->
                counterStore.incrementAndGet(version, windowStart, rule.name(), source));
        try {
            return future.get(storeTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new StoreTimeoutException(
                    "counter store call timed out after " + storeTimeoutMillis + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreUnavailableException("counter store call interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof StoreException storeException) {
                throw storeException;
            }
            throw new StoreUnavailableException("counter store call failed", cause);
        }
    }

    private void persistBan(BanState state) {
        Future<?> future = storeExecutors.submit(() -> banStore.put(state));
        try {
            future.get(storeTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new StoreTimeoutException(
                    "ban store write timed out after " + storeTimeoutMillis + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreUnavailableException("ban store write interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof StoreException storeException) {
                throw storeException;
            }
            throw new StoreUnavailableException("ban store write failed", cause);
        }
    }

    private RateLimitDecision finish(RateLimitDecision decision) {
        decisionLog.append(decision);
        return decision;
    }

    private void recordMetrics(RateLimitDecision decision) {
        if (!decision.allowed()) {
            metrics.recordRejected(decision.reasonCode());
        } else if (decision.degraded()) {
            metrics.recordDegradedOpen();
        } else {
            metrics.recordAllowed();
        }
    }

    private RateLimitDecision allow(RequestEntry request, RateLimitPolicy policy,
                                    String ruleName, long windowStart, long limit,
                                    long observedCount, long now, String source) {
        RateLimitDecision decision = new RateLimitDecision(
                request.requestId(), true, false, "ALLOWED",
                "within limits",
                policy.version(), ruleName, windowStart, observedCount, limit, source,
                0, 0L, now);
        log.info("requestId={} inputs={} policy={} rule={} windowStart={} count={}/{} => ALLOW source={}",
                request.requestId(), inputs(request), policy.version(), ruleName,
                windowStart, observedCount, limit, source);
        return decision;
    }

    private List<RateLimitRule> matchingRules(RateLimitPolicy policy, String path) {
        List<RateLimitRule> result = new ArrayList<>();
        for (RateLimitRule rule : policy.rules()) {
            List<String> patterns = rule.pathPatterns();
            if (patterns == null || patterns.isEmpty()) {
                result.add(rule);
                continue;
            }
            for (String pattern : patterns) {
                if (matchesPath(pattern, path)) {
                    result.add(rule);
                    break;
                }
            }
        }
        return result;
    }

    /** Match a simple glob pattern ({@code *} wildcard) against the request path. */
    static boolean matchesPath(String pattern, String path) {
        if (pattern == null || pattern.isBlank()) {
            return true;
        }
        String actual = path == null ? "/" : path;
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        regex.append('$');
        return Pattern.matches(regex.toString(), actual);
    }

    private static String inputs(RequestEntry r) {
        return "{ip=" + r.clientIp() + ", method=" + r.method() + ", path=" + r.path()
                + ", user=" + r.userId() + ", token=" + r.token() + "}";
    }

    private void sweepExpired(long now) {
        if (dedup.size() < SWEEP_THRESHOLD) {
            return;
        }
        dedup.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
    }

    @PreDestroy
    void shutdown() {
        storeExecutors.shutdownNow();
    }
}
