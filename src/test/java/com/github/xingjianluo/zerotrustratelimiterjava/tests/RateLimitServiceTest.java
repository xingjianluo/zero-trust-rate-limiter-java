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
import com.github.xingjianluo.zerotrustratelimiterjava.model.RejectReason;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestEntry;
import com.github.xingjianluo.zerotrustratelimiterjava.policy.FilePolicyStore;
import com.github.xingjianluo.zerotrustratelimiterjava.policy.PolicyManager;
import com.github.xingjianluo.zerotrustratelimiterjava.store.BanStore;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Core engine tests: window accuracy under concurrency, hot-update boundaries and bans. */
class RateLimitServiceTest {

    private static final Logger log = LoggerFactory.getLogger(RateLimitServiceTest.class);

    static final class ManualClock implements TimeSource {
        final AtomicLong millis = new AtomicLong(1_000_000L);
        final AtomicLong nano = new AtomicLong(0L);

        @Override
        public long currentTimeMillis() {
            return millis.get();
        }

        @Override
        public long nanoTime() {
            return nano.getAndAdd(1_000_000L);
        }
    }

    @TempDir
    Path tempDir;

    ManualClock clock;
    FaultState faultState;
    InMemoryCounterStore counterStore;
    BanStore banStore;
    PolicyManager policyManager;
    DecisionMetrics metrics;
    RateLimitService service;

    RateLimitPolicy policy(String version, long limit, long windowSeconds,
                           long effectiveAt) {
        RateLimitRule rule = new RateLimitRule(
                "per-ip", Dimension.IP, limit, windowSeconds, List.of("/api/*"));
        return new RateLimitPolicy(version, clock.currentTimeMillis(), effectiveAt,
                List.of(rule), 3, 3, 30L, 2L, 60L);
    }

    RateLimitService buildService(FailStrategy strategy) {
        return new RateLimitService(counterStore, banStore, policyManager, clock,
                strategy, 200L, 30_000L, metrics, new DecisionLog(100));
    }

    @BeforeEach
    void setUp() {
        clock = new ManualClock();
        faultState = new FaultState();
        counterStore = new InMemoryCounterStore(faultState);
        banStore = new FileBanStore(tempDir.resolve("bans.json"), faultState);
        PolicyManager pm = new PolicyManager(
                new FilePolicyStore(tempDir.resolve("policies.jsonl"), faultState), clock);
        pm.seedIfEmpty(policy("v1", 5L, 10L, 0L));
        policyManager = pm;
        metrics = new DecisionMetrics();
        service = buildService(FailStrategy.CLOSED);
    }

    RequestEntry request(String ip, String path) {
        return new RequestEntry(UUID.randomUUID().toString(), ip, path,
                "GET", null, null);
    }

    @Test
    void exactLimitBoundaryThenRejectWithDistinctReason() {
        RateLimitDecision last = null;
        for (int i = 0; i < 5; i++) {
            last = service.decide(request("1.1.1.1", "/api/x"));
            assertTrue(last.allowed(), "request " + i + " must be allowed");
        }
        RateLimitDecision rejected = service.decide(request("1.1.1.1", "/api/x"));
        log.info("requestId={} inputs=ip:1.1.1.1 count:6 result=allowed:{} reason:{}",
                rejected.requestId(), rejected.allowed(), rejected.reasonCode());
        assertFalse(rejected.allowed());
        assertEquals("RATE_LIMITED", rejected.reasonCode());
        assertEquals(6L, rejected.observedCount());
        assertEquals(5L, rejected.limit());
    }

    @Test
    void windowSwitchResetsCounterAtDeterministicBoundary() {
        for (int i = 0; i < 6; i++) {
            service.decide(request("2.2.2.2", "/api/x"));
        }
        RateLimitDecision before = service.decide(request("2.2.2.2", "/api/x"));
        assertFalse(before.allowed());

        // Advance to the next fixed window (windowSeconds=10, epoch aligned).
        long newWindow = ((clock.currentTimeMillis() / 10_000L) + 1) * 10_000L;
        clock.millis.set(newWindow);
        RateLimitDecision after = service.decide(request("2.2.2.2", "/api/x"));
        log.info("requestId={} inputs=ip:2.2.2.2 windowStart:{} result=allowed:{} observed:{}",
                after.requestId(), after.windowStartMillis(), after.allowed(),
                after.observedCount());
        assertTrue(after.allowed());
        assertEquals(1L, after.observedCount());
        assertEquals(newWindow, after.windowStartMillis());
    }

    @Test
    void concurrentRequestsAreCountedExactlyAtWindowBoundary() throws Exception {
        int threads = 20;
        int perThread = 5; // 100 total, limit 5 -> exactly 5 allowed, 95 rejected
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                for (int i = 0; i < perThread; i++) {
                    RateLimitDecision d = service.decide(request("9.9.9.9", "/api/x"));
                    if (d.allowed()) {
                        allowed.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                    }
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        log.info("requestId=test-concurrent inputs=100-parallel result=allowed:{} rejected:{}",
                allowed.get(), rejected.get());
        assertEquals(5, allowed.get());
        assertEquals(95, rejected.get());
    }

    @Test
    void policyHotUpdateTakesEffectAtNewWindowAndKeepsOldHistory() {
        // Exhaust old limit (5) in the current window.
        for (int i = 0; i < 6; i++) {
            service.decide(request("3.3.3.3", "/api/x"));
        }
        RateLimitPolicy oldActive = policyManager.activeAt(clock.currentTimeMillis());
        long nextWindow = ((clock.currentTimeMillis() / 10_000L) + 1) * 10_000L;
        policyManager.publish(policy("v2", 2L, 10L, nextWindow));

        // Still inside the old window: old policy governs and source is rate limited.
        RateLimitDecision inOldWindow = service.decide(request("3.3.3.3", "/api/x"));
        assertEquals("v1", inOldWindow.policyVersion());
        assertFalse(inOldWindow.allowed());

        // Exactly at the boundary instant: new policy governs with limit 2.
        clock.millis.set(nextWindow);
        RateLimitDecision first = service.decide(request("3.3.3.3", "/api/x"));
        RateLimitDecision second = service.decide(request("3.3.3.3", "/api/x"));
        RateLimitDecision third = service.decide(request("3.3.3.3", "/api/x"));
        log.info("requestId={} inputs=post-hotupdate windowStart:{} result=v:{} allowed:{}",
                third.requestId(), third.windowStartMillis(), third.policyVersion(),
                third.allowed());
        assertTrue(first.allowed());
        assertTrue(second.allowed());
        assertFalse(third.allowed());
        assertEquals("v2", third.policyVersion());
        assertEquals(2L, third.limit());

        // Old version retained with its own counters (history not rewritten).
        assertEquals(2, policyManager.history().size());
        long oldWindow = (clock.currentTimeMillis() / 10_000L - 1) * 10_000L;
        assertEquals(7L, counterStore.get("v1", oldWindow, "per-ip", "3.3.3.3"));
    }

    @Test
    void repeatedViolationsEscalateBanAndManualUnbanIsImmediate() {
        // Window 1: 6 requests -> 1 violation recorded (only the rejected one counts).
        // violationsForBan=3, so three rejected windows are needed for level 1.
        for (int window = 0; window < 3; window++) {
            long start = window * 10_000L;
            clock.millis.set(1_000_000L + start);
            for (int i = 0; i < 6; i++) {
                service.decide(request("4.4.4.4", "/api/x"));
            }
        }
        RateLimitDecision banned = service.decide(request("4.4.4.4", "/api/x"));
        log.info("requestId={} inputs=post-3-violations result=reason:{} level:{}",
                banned.requestId(), banned.reasonCode(), banned.banLevel());
        assertEquals("BANNED", banned.reasonCode());
        assertEquals(1, banned.banLevel());

        // Manual unban takes effect immediately, counters are fresh in a new window too.
        service.unban("4.4.4.4");
        long afterUnbanWindow = 1_000_000L + 3 * 10_000L;
        clock.millis.set(afterUnbanWindow);
        RateLimitDecision freed = service.decide(request("4.4.4.4", "/api/x"));
        log.info("requestId={} inputs=after-unban result=allowed:{} reason:{}",
                freed.requestId(), freed.allowed(), freed.reasonCode());
        assertTrue(freed.allowed());
    }
}
