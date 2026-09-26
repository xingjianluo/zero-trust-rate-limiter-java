package com.github.xingjianluo.zerotrustratelimiterjava.core;

import com.github.xingjianluo.zerotrustratelimiterjava.model.BanState;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RateLimitPolicy;

/**
 * Pure functions for ban escalation, expiry and recovery.
 *
 * <p>Deterministic state machine driven by wall-clock timestamps only, so restart or
 * reload never changes the outcome for the same stored state and instant:
 *
 * <ul>
 *   <li>A violation is recorded only when a request exceeds a rate-limit rule.
 *       Requests rejected because the source is already banned do not add violations.</li>
 *   <li>Every {@code violationsForBan} violations, the ban level increases by one and a
 *       new ban window starts. Level N duration =
 *       {@code baseBanSeconds * banMultiplier^(N-1)}, capped at {@code maxBanLevel}.</li>
 *   <li>After a ban window passes the source can make requests again, but the level is
 *       retained. The level (and violation counter) fully resets to zero only when the
 *       source stays violation-free for {@code violationRecoverySeconds} after its last
 *       violation. This gives a stable upgrade/recovery ladder.</li>
 *   <li>Manual unban deletes the stored state entirely and is immediately effective.</li>
 * </ul>
 */
public final class BanEvaluator {
    private BanEvaluator() {
    }

    /** True when the source is currently banned at {@code nowMillis}. */
    public static boolean isBanned(BanState state, long nowMillis) {
        return state != null && state.banUntilMillis() > nowMillis;
    }

    /**
     * Apply one additional rate-limit violation.
     *
     * @return the resulting state; when this violation crosses an escalation boundary the
     *         returned state has an incremented level and a future {@code banUntilMillis}.
     */
    public static BanState registerViolation(BanState current, RateLimitPolicy policy,
                                             String source, long nowMillis) {
        BanState base = expireIfRecovered(current, policy, nowMillis);
        int violations = (base == null ? 0 : base.violations()) + 1;
        int level = base == null ? 0 : base.banLevel();
        long banUntil = base == null ? 0L : base.banUntilMillis();

        if (violations % policy.violationsForBan() == 0 && level < policy.maxBanLevel()) {
            level++;
            banUntil = nowMillis + banDurationMillis(policy, level);
        }
        return new BanState(source, violations, level, banUntil, nowMillis);
    }

    /**
     * Reset the escalation ladder when the source has been violation-free for the
     * configured recovery period. If still inside the recovery period, only the active
     * ban flag is lifted once its window elapsed; the level is retained.
     */
    public static BanState expireIfRecovered(BanState current, RateLimitPolicy policy,
                                             long nowMillis) {
        if (current == null) {
            return null;
        }
        boolean activeBan = current.banUntilMillis() > nowMillis;
        long recoveredAt = current.lastViolationMillis()
                + policy.violationRecoverySeconds() * 1000L;
        if (!activeBan && nowMillis >= recoveredAt) {
            // Full reset: ban window elapsed and no violations for the recovery period.
            return null;
        }
        if (!activeBan && current.banUntilMillis() != 0L
                && nowMillis >= current.banUntilMillis()) {
            // Ban window elapsed but recovery window still open: keep level/violations,
            // just mark that the source is currently allowed through.
            return new BanState(current.source(), current.violations(),
                    current.banLevel(), 0L, current.lastViolationMillis());
        }
        return current;
    }

    /** Duration of a ban at a given level: base * multiplier^(level-1) milliseconds. */
    public static long banDurationMillis(RateLimitPolicy policy, int level) {
        long seconds = policy.baseBanSeconds();
        for (int i = 1; i < level; i++) {
            seconds = Math.multiplyExact(seconds, policy.banMultiplier());
        }
        return seconds * 1000L;
    }
}
