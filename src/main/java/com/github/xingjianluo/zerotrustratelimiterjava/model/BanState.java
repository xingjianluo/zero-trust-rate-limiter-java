package com.github.xingjianluo.zerotrustratelimiterjava.model;

/** Persistent ban state for a source identity. */
public record BanState(
        String source,
        int violations,
        int banLevel,
        long banUntilMillis,
        long lastViolationMillis) {
}
