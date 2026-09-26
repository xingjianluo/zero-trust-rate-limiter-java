package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.github.xingjianluo.zerotrustratelimiterjava.core.BanEvaluator;
import com.github.xingjianluo.zerotrustratelimiterjava.core.DecisionLog;
import com.github.xingjianluo.zerotrustratelimiterjava.core.DecisionMetrics;
import com.github.xingjianluo.zerotrustratelimiterjava.core.RateLimitService;
import com.github.xingjianluo.zerotrustratelimiterjava.core.TimeSource;
import com.github.xingjianluo.zerotrustratelimiterjava.model.BanState;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies ban state and policy history keep consistent semantics across restart. */
class PersistenceRestartTest {

    private static final Logger log = LoggerFactory.getLogger(PersistenceRestartTest.class);

    static final class ManualClock implements TimeSource {
        final AtomicLong millis;

        ManualClock(long start) {
            this.millis = new AtomicLong(start);
        }

        @Override
        public long currentTimeMillis() {
            return millis.get();
        }

        @Override
        public long nanoTime() {
            return System.nanoTime();
        }
    }

    private RateLimitPolicy policy() {
        return new RateLimitPolicy("v1", 1_000_000L, 0L,
                List.of(new RateLimitRule("r", Dimension.IP, 5L, 10L, List.of("/api/*"))),
                3, 3, 30L, 2L, 60L);
    }

    private RequestEntry req(String ip) {
        return new RequestEntry(UUID.randomUUID().toString(), ip, "/api/x",
                "GET", null, null);
    }

    @Test
    void banSurvivesRestartAndManualUnbanDoesNot(@TempDir Path dir) {
        Path banFile = dir.resolve("bans.json");
        Path policyFile = dir.resolve("p.jsonl");

        // --- First "process": create a level-1 ban through repeated violations. ---
        ManualClock clock1 = new ManualClock(1_000_000L);
        FaultState f1 = new FaultState();
        FileBanStore bans1 = new FileBanStore(banFile, f1);
        PolicyManager pm1 = new PolicyManager(new FilePolicyStore(policyFile, f1), clock1);
        pm1.seedIfEmpty(policy());
        RateLimitService svc1 = new RateLimitService(
                new InMemoryCounterStore(f1), bans1, pm1, clock1,
                FailStrategy.CLOSED, 200L, 30_000L,
                new DecisionMetrics(), new DecisionLog(50));
        for (int w = 0; w < 3; w++) {
            clock1.millis.set(1_000_000L + w * 10_000L);
            for (int i = 0; i < 6; i++) {
                svc1.decide(req("7.7.7.7"));
            }
        }
        RateLimitDecision bannedBefore = svc1.decide(req("7.7.7.7"));
        assertEquals("BANNED", bannedBefore.reasonCode());

        // --- "Restart": brand-new stores/managers reading the same files. ---
        ManualClock clock2 = new ManualClock(1_000_000L + 5_000L);
        FaultState f2 = new FaultState();
        FileBanStore bans2 = new FileBanStore(banFile, f2);
        PolicyManager pm2 = new PolicyManager(new FilePolicyStore(policyFile, f2), clock2);
        RateLimitService svc2 = new RateLimitService(
                new InMemoryCounterStore(f2), bans2, pm2, clock2,
                FailStrategy.CLOSED, 200L, 30_000L,
                new DecisionMetrics(), new DecisionLog(50));

        // Policy history is reloaded (default not re-seeded, no duplicate).
        assertEquals(1, pm2.history().size());
        assertEquals("v1", pm2.activeAt(clock2.currentTimeMillis()).version());

        // Ban still enforced with the same level/expiry computed deterministically.
        BanState restored = bans2.get("7.7.7.7");
        log.info("requestId=test-restart inputs=source:7.7.7.7 result=restored={}", restored);
        assertTrue(BanEvaluator.isBanned(restored, clock2.currentTimeMillis()));
        assertEquals(1, restored.banLevel());
        RateLimitDecision bannedAfter = svc2.decide(req("7.7.7.7"));
        assertEquals("BANNED", bannedAfter.reasonCode());

        // After the ban window elapses past restart, the source is allowed again
        // (level retained until the recovery window, same semantics as before restart).
        clock2.millis.set(restored.banUntilMillis() + 1L);
        RateLimitDecision expired = svc2.decide(req("7.7.7.7"));
        log.info("requestId={} inputs=post-restart-after-ban-window result=allowed:{}",
                expired.requestId(), expired.allowed());
        assertTrue(expired.allowed());

        // Manual unban on the restarted process is immediate and persisted.
        svc2.unban("7.7.7.7");
        FileBanStore bans3 = new FileBanStore(banFile, new FaultState());
        assertFalse(BanEvaluator.isBanned(bans3.get("7.7.7.7"),
                clock2.currentTimeMillis()));
    }
}
