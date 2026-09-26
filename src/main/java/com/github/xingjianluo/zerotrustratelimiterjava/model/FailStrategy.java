package com.github.xingjianluo.zerotrustratelimiterjava.model;

/** Degradation strategy when the rate-limit state store is unavailable or too slow. */
public enum FailStrategy {
    /** Allow the request through, but mark the decision as degraded and record metrics. */
    OPEN,
    /** Reject the request with a distinguishable reason. */
    CLOSED
}
