package com.github.xingjianluo.zerotrustratelimiterjava.model;

import java.util.List;

/** Immutable versioned rate-limit policy document. */
public record RateLimitPolicy(
        String version,
        long createdAtMillis,
        long effectiveAtMillis,
        List<RateLimitRule> rules,
        int violationsForBan,
        int maxBanLevel,
        long baseBanSeconds,
        long banMultiplier,
        long violationRecoverySeconds) {
}
