package com.github.xingjianluo.zerotrustratelimiterjava.model;

/** Immutable, explainable decision result for one request. */
public record RateLimitDecision(
        String requestId,
        boolean allowed,
        boolean degraded,
        String reasonCode,
        String reasonDetail,
        String policyVersion,
        String ruleName,
        long windowStartMillis,
        long observedCount,
        long limit,
        String source,
        int banLevel,
        long banUntilMillis,
        long decisionMillis) {
}
