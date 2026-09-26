package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.github.xingjianluo.zerotrustratelimiterjava.core.DecisionLog;
import com.github.xingjianluo.zerotrustratelimiterjava.core.DecisionMetrics;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.TimeSource;
import com.github.xingjianluo.zerotrustratelimiterjava.model.Dimension;
import com.github.xingjianluo.zerotrustratelimiterjava.model.FailStrategy;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RateLimitDecision;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RateLimitPolicy;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RateLimitRule;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestEntry;
import com.github.xingjianluo.zerotrustratelimiterjava.policy.FilePolicyStore;
import com.github.xingjianluo.zerotrustratelimiterjava.policy.PolicyManager;
import com.github.xingjianluo.zerotrustratelimiterjava.store.FaultState;
import com.github.xingjianluo.zerotrustratelimiterjava.store.FileBanStore;
import com.github.xingjianluo.zerotrustratelimiterjava.store.InMemoryCounterStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Degradation tests: store unavailable, latency over threshold, and request dedup. */
class DegradationTest {

    private static final Logger log = LoggerFactory.getLogger(DegradationTest.class);

    static final class ManualClock implements TimeSource {
        final AtomicLong millis = new AtomicLong(1_000_000L);

        @Override
        public long currentTimeMillis() {
            return millis.get();
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    }

    @TempDir
    Path tempDir;

    ManualClock clock;
    FaultState faultState;
    InMemoryCounterStore counterStore;
    FileBanStore banStore;
    PolicyManager policyManager;

    RateLimitPolicy policy() {
        return new RateLimitPolicy("v1", 1_000_000L, 0L,
                List.of(new RateLimitRule("r", Dimension.IP, 5L, 10L, List.of("/api/*"))),
                3, 3, 30L, 2L, 60L);
    }

    RateLimitService service(FailStrategy strategy, long timeoutMillis) {
        DecisionMetrics metrics = new DecisionMetrics();
        return new RateLimitService(counterStore, banStore, policyManager, clock,
                strategy, timeoutMillis, 30_000L, metrics, new DecisionLog(50));
    }

    RequestEntry request(String id, String ip) {
        return new RequestEntry(id == null ? UUID.randomUUID().toString() : id,
                ip, "/api/x", "GET", null, null);
    }

    @BeforeEach
    void setUp() {
        clock = new ManualClock();
        faultState = new FaultState();
        counterStore = new InMemoryCounterStore(faultState);
        banStore = new FileBanStore(tempDir.resolve("bans.json"), faultState);
        PolicyManager pm = new PolicyManager(
                new FilePolicyStore(tempDir.resolve("p.jsonl"), faultState), clock);
        pm.seedIfEmpty(policy());
        policyManager = pm;
    }

    @Test
    void failClosedRejectsUnavailableStoreWithDistinctReason() {
        RateLimitService svc = service(FailStrategy.CLOSED, 200L);
        faultState.setUnavailable(true);
        RateLimitDecision d = svc.decide(request(null, "1.1.1.1"));
        log.info("requestId={} inputs=fault:unavailable strategy:CLOSED result=allowed:{} reason:{} detail:{}",
                d.requestId(), d.allowed(), d.reasonCode(), d.reasonDetail());
        assertFalse(d.allowed());
        assertEquals("FAIL_CLOSED_UNAVAILABLE", d.reasonCode());
        assertFalse(d.degraded());
    }

    @Test
    void failClosedRejectsSlowStoreWithTimeoutReason() {
        RateLimitService svc = service(FailStrategy.CLOSED, 100L);
        faultState.setForcedDelayMillis(400L);
        long start = System.nanoTime();
        RateLimitDecision d = svc.decide(request(null, "2.2.2.2"));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        log.info("requestId={} inputs=fault:delay400ms threshold:100ms elapsedMs:{} result=reason:{}",
                d.requestId(), elapsedMs, d.reasonCode());
        assertFalse(d.allowed());
        assertEquals("FAIL_CLOSED_TIMEOUT", d.reasonCode());
        // The caller must not pay the full store delay.
        assertTrue(elapsedMs < 350L, "decision should return near the timeout, was "
                + elapsedMs + "ms");
    }

    @Test
    void failOpenAllowsButFlagsDegradedAndMetersIt() {
        DecisionMetrics metrics = new DecisionMetrics();
        RateLimitService svc = new RateLimitService(counterStore, banStore, policyManager,
                clock, FailStrategy.OPEN, 200L, 30_000L, metrics, new DecisionLog(50));
        faultState.setUnavailable(true);
        RateLimitDecision d = svc.decide(request(null, "3.3.3.3"));
        log.info("requestId={} inputs=fault:unavailable strategy:OPEN result=allowed:{} degraded:{} reason:{}",
                d.requestId(), d.allowed(), d.degraded(), d.reasonCode());
        assertTrue(d.allowed());
        assertTrue(d.degraded());
        assertEquals("DEGRADED_OPEN", d.reasonCode());
        assertEquals(1L, metrics.snapshot().get("degradedOpen"));
        faultState.reset();
        svc.decide(request(null, "3.3.3.3"));
        assertEquals(1L, metrics.snapshot().get("allowed"));
    }

    @Test
    void sameRequestIdAlwaysGetsOneConsistentVerdict() {
        RateLimitService svc = service(FailStrategy.CLOSED, 200L);
        String id = UUID.randomUUID().toString();
        RateLimitDecision first = svc.decide(request(id, "5.5.5.5"));

        // Even if the store breaks between retries, the same request id gets the
        // identical verdict: no contradictory conclusion for one request.
        faultState.setUnavailable(true);
        RateLimitDecision replay = svc.decide(request(id, "5.5.5.5"));
        log.info("requestId={} inputs=replay-after-fault result=firstReason:{} replayReason:{}",
                id, first.reasonCode(), replay.reasonCode());
        assertEquals(first.reasonCode(), replay.reasonCode());
        assertEquals(first.allowed(), replay.allowed());
        assertEquals(first.policyVersion(), replay.policyVersion());

        // A different request id under the fault gets the fail-closed rejection.
        RateLimitDecision other = svc.decide(request(null, "5.5.5.5"));
        assertNotEquals(id, other.requestId());
        assertEquals("FAIL_CLOSED_UNAVAILABLE", other.reasonCode());
    }
}
