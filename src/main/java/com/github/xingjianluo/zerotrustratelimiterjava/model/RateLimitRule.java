package com.github.xingjianluo.zerotrustratelimiterjava.model;

import java.util.List;

/** One rate-limit rule: at most {@code maxRequests} requests per {@code windowSeconds}. */
public record RateLimitRule(
        String name,
        Dimension dimension,
        long maxRequests,
        long windowSeconds,
        List<String> pathPatterns) {
}
