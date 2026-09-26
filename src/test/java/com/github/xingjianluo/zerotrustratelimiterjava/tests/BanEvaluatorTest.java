package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.github.xingjianluo.zerotrustratelimiterjava.core.BanEvaluator;
import com.github.xingjianluo.zerotrustratelimiterjava.model.BanState;
import com.github.xingjianluo.zerotrustratelimiterjava.model.Dimension;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RateLimitPolicy;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RateLimitRule;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Escalation, expiry and recovery boundary tests for the deterministic ban ladder. */
class BanEvaluatorTest {

    private static final Logger log = LoggerFactory.getLogger(BanEvaluatorTest.class);

    private RateLimitPolicy policy() {
        return new RateLimitPolicy("t", 0L, 0L,
                List.of(new RateLimitRule("r", Dimension.IP, 1L, 10L, List.of("/*"))),
                3, 3, 30L, 2L, 60L);
    }

    @Test
    void normalRequestsDoNotBan() {
        RateLimitPolicy p = policy();
        BanState s = null;
        long t = 1000L;
        s = BanEvaluator.registerViolation(s, p, "ip1", t);
        s = BanEvaluator.registerViolation(s, p, "ip1", t + 1000L);
        log.info("requestId=test-normal inputs=violations:2 result={}", s);
        assertEquals(2, s.violations());
        assertFalse(BanEvaluator.isBanned(s, t + 2000L));
    }

    @Test
    void thirdViolationEscalatesToLevel1For30Seconds() {
        RateLimitPolicy p = policy();
        long t0 = 10_000L;
        BanState s = null;
        for (int i = 1; i <= 3; i++) {
            s = BanEvaluator.registerViolation(s, p, "ip1", t0 + i * 1000L);
        }
        log.info("requestId=test-escalate inputs=violations:3 result={}", s);
        assertEquals(1, s.banLevel());
        assertEquals(t0 + 3000L + 30_000L, s.banUntilMillis());
        assertTrue(BanEvaluator.isBanned(s, t0 + 4000L));
    }

    @Test
    void banLevelDoublesPerEscalationAndCapsAtMaxLevel() {
        RateLimitPolicy p = policy();
        long t = 0L;
        BanState s = null;
        // 9 violations -> levels 1,2,3 (30s,60s,120s)
        for (int i = 1; i <= 9; i++) {
            t += 1000L;
            s = BanEvaluator.registerViolation(s, p, "ip1", t);
        }
        log.info("requestId=test-ladder inputs=violations:9 result={}", s);
        assertEquals(3, s.banLevel());
        assertEquals(9, s.violations());
        // 10th..12th violations do not escalate beyond max level
        for (int i = 0; i < 3; i++) {
            t += 1000L;
            s = BanEvaluator.registerViolation(s, p, "ip1", t);
        }
        assertEquals(3, s.banLevel());
    }

    @Test
    void banExpiresButLevelIsRetainedUntilRecoveryPeriod() {
        RateLimitPolicy p = policy();
        long t0 = 0L;
        BanState s = BanEvaluator.registerViolation(null, p, "ip1", t0);
        s = BanEvaluator.registerViolation(s, p, "ip1", t0 + 1000L);
        s = BanEvaluator.registerViolation(s, p, "ip1", t0 + 2000L);
        assertTrue(BanEvaluator.isBanned(s, t0 + 3000L));

        // After ban window (30s) but before recovery (60s after last violation): allowed,
        // level retained.
        long afterBan = t0 + 2000L + 31_000L;
        BanState expired = BanEvaluator.expireIfRecovered(s, p, afterBan);
        log.info("requestId=test-expire inputs=at:{} result={}", afterBan, expired);
        assertFalse(BanEvaluator.isBanned(expired, afterBan));
        assertEquals(1, expired.banLevel());
        assertEquals(3, expired.violations());

        // After the full recovery window without new violations: everything resets.
        long afterRecovery = t0 + 2000L + 60_000L + 1L;
        assertNull(BanEvaluator.expireIfRecovered(s, p, afterRecovery));
    }

    @Test
    void banDurationIsStableAndGeometric() {
        RateLimitPolicy p = policy();
        assertEquals(30_000L, BanEvaluator.banDurationMillis(p, 1));
        assertEquals(60_000L, BanEvaluator.banDurationMillis(p, 2));
        assertEquals(120_000L, BanEvaluator.banDurationMillis(p, 3));
    }
}
