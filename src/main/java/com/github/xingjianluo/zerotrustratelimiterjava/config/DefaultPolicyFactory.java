package com.github.xingjianluo.zerotrustratelimiterjava.config;

import com.github.xingjianluo.zerotrustratelimiterjava.core.TimeSource;
import com.github.xingjianluo.zerotrustratelimiterjava.model.Dimension;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RateLimitPolicy;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RateLimitRule;

import java.util.List;

/** Builds the built-in default policy used on first startup. */
public final class DefaultPolicyFactory {

    public static final String DEFAULT_VERSION = "default-v1";

    private DefaultPolicyFactory() {
    }

    public static RateLimitPolicy create(TimeSource timeSource) {
        long now = timeSource.currentTimeMillis();
        List<RateLimitRule> rules = List.of(
                new RateLimitRule("per-ip-api", Dimension.IP, 5L, 10L, List.of("/api/*")),
                new RateLimitRule("per-ip-user", Dimension.IP_PATH, 3L, 10L,
                        List.of("/api/orders*")));
        return new RateLimitPolicy(
                DEFAULT_VERSION,
                now,
                now,
                rules,
                3,
                3,
                30L,
                2L,
                60L);
    }
}
