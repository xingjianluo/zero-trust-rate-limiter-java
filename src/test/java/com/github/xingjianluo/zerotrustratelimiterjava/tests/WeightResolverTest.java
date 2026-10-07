package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.github.xingjianluo.zerotrustratelimiterjava.core.WeightResolver;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestContext;
import com.github.xingjianluo.zerotrustratelimiterjava.model.WeightRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 权重匹配规则：精确优先、最长前缀、未命中为 1。 */
class WeightResolverTest {

    private final WeightResolver resolver = new WeightResolver();

    private RequestContext req(String path) {
        return new RequestContext("r-1", "1.1.1.1", null, path, "GET");
    }

    @Test
    void emptyOrNullRulesGiveDefaultOne() {
        assertEquals(1, resolver.resolve(req("/x"), List.of()));
        assertEquals(1, resolver.resolve(req("/x"), null));
    }

    @Test
    void exactMatchWinsOverPrefix() {
        List<WeightRule> rules = List.of(
                new WeightRule("/api", 3),
                new WeightRule("/api/export", 9));
        assertEquals(9, resolver.resolve(req("/api/export"), rules));
        assertEquals(3, resolver.resolve(req("/api/other"), rules));
    }

    @Test
    void longestPrefixWins() {
        List<WeightRule> rules = List.of(
                new WeightRule("/api", 2),
                new WeightRule("/api/export", 5));
        assertEquals(5, resolver.resolve(req("/api/export/2026/x"), rules));
        assertEquals(2, resolver.resolve(req("/api/users/me"), rules));
    }

    @Test
    void nonMatchingSimilarPathGetsDefaultOne() {
        List<WeightRule> rules = List.of(new WeightRule("/api/export", 5));
        // 不同前缀的路径不命中
        assertEquals(1, resolver.resolve(req("/web/export/x"), rules));
        assertEquals(1, resolver.resolve(req("/other"), rules));
    }
}
