package com.github.xingjianluo.zerotrustratelimiterjava.core;

import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestContext;
import com.github.xingjianluo.zerotrustratelimiterjava.model.WeightRule;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 按可配置权重规则解析单个请求消耗的配额量。
 *
 * <p>匹配规则（确定、可解释）：
 * <ol>
 *   <li>路径与某条规则 {@link WeightRule#path()} 完全相等 → 命中（最高优先级）；</li>
 *   <li>否则按前缀匹配，多条前缀命中时取最长前缀（如配置 {@code /api/export} 可命中
 *       {@code /api/export/2026/report}，而不会命中 {@code /api/exported-data}
 *       ——只要不同时存在覆盖后者的更短规则）；</li>
 *   <li>均未命中时权重为 1——未配置权重的场景与“每请求一条”的旧行为完全一致。</li>
 * </ol>
 * 权重在窗口开始后不影响该窗口内既有用量：用量是按请求时刻生效的权重累加的历史事实，
 * 策略热更新只对之后新开始的窗口生效（由策略版本命名空间保证）。
 */
@Component
public class WeightResolver {

    /** 默认权重：未配置/未命中任何规则时每请求消耗 1。 */
    public static final long DEFAULT_WEIGHT = 1L;

    public long resolve(RequestContext request, List<WeightRule> rules) {
        if (rules == null || rules.isEmpty() || request.path() == null) {
            return DEFAULT_WEIGHT;
        }
        String path = request.path();
        WeightRule bestPrefix = null;
        for (WeightRule rule : rules) {
            if (rule == null || rule.path() == null) {
                continue;
            }
            if (path.equals(rule.path())) {
                return rule.weight(); // 精确匹配立即胜出
            }
            if (path.startsWith(rule.path())) {
                if (bestPrefix == null
                        || rule.path().length() > bestPrefix.path().length()) {
                    bestPrefix = rule;
                }
            }
        }
        return bestPrefix == null ? DEFAULT_WEIGHT : bestPrefix.weight();
    }

    /** 视图/测试用：返回命中规则的路径（未命中返回 null），匹配优先级与 {@link #resolve} 一致。 */
    public String matchedRule(RequestContext request, List<WeightRule> rules) {
        if (rules == null || rules.isEmpty() || request.path() == null) {
            return null;
        }
        String path = request.path();
        String bestPrefix = null;
        for (WeightRule rule : rules) {
            if (rule == null || rule.path() == null) {
                continue;
            }
            if (path.equals(rule.path())) {
                return rule.path();
            }
            if (path.startsWith(rule.path())) {
                if (bestPrefix == null
                        || rule.path().length() > bestPrefix.length()) {
                    bestPrefix = rule.path();
                }
            }
        }
        return bestPrefix;
    }
}
