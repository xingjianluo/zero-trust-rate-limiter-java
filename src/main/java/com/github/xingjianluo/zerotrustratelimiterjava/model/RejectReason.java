package com.github.xingjianluo.zerotrustratelimiterjava.model;

/** Distinguishable reason codes explaining why a request was rejected. */
public enum RejectReason {
    RATE_LIMITED,
    BANNED,
    FAIL_CLOSED_UNAVAILABLE,
    FAIL_CLOSED_TIMEOUT
}
