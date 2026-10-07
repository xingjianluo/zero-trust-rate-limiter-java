package com.github.xingjianluo.zerotrustratelimiterjava.core;

import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 请求权重解析：按策略快照中固化的「路径前缀 -> 权重」做最长前缀匹配。
 *
 * <p>规则：
 * <ul>
 *   <li>命中多个前缀时取最长（最具体）的一个，例如同时配置 {@code /api} 与 {@code /api/export}，
 *       请求 {@code /api/export/big} 取后者权重；</li>
 *   <li>没有任何前缀匹配（或策略未配置权重）时权重为 1，与旧行为完全一致；</li>
 *   <li>权重表在策略快照发布时固化，调整只对之后新开始的窗口生效（版本化计数命名空间保证），
 *       已产生的窗口用量与历史判定不被改写。</li>
 * </ul>
 */
@Component
public class WeightResolver {

    /** 默认权重：未配置/未匹配到任何前缀的请求按 1 个单位消耗配额。 */
    public static final long DEFAULT_WEIGHT = 1L;

    /**
     * @param policy  当前生效策略快照（权重表已固化其中）
     * @param request 待判定请求
     * @return 本次请求消耗的配额量（正整数）
     */
    public long resolve(RateLimitPolicy policy, RequestContext request) {
        Map<String, Long> weights = policy.weights();
        if (weights == null || weights.isEmpty()) {
            return DEFAULT_WEIGHT;
        }
        String path = request.path() == null ? "" : request.path();
        long best = DEFAULT_WEIGHT;
        int bestLength = -1;
        for (Map.Entry<String, Long> e : weights.entrySet()) {
            String prefix = e.getKey();
            if (path.startsWith(prefix) && prefix.length() > bestLength) {
                bestLength = prefix.length();
                best = e.getValue();
            }
        }
        return best;
    }
}
