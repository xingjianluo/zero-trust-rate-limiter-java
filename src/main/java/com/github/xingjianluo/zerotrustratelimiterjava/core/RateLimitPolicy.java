package com.github.xingjianluo.zerotrustratelimiterjava.core;

import com.github.xingjianluo.zerotrustratelimiterjava.config.RateLimitProperties;
import com.github.xingjianluo.zerotrustratelimiterjava.model.FailMode;
import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 不可变策略快照。每次热更新生成新快照与新版本号；判定过程只引用单一快照，
 * 保证一次请求不会混用新旧策略，历史判定也不会被静默改写。
 *
 * <p>配额模型：
 * <ul>
 *   <li>{@link #tiers}：分层配额，同一请求同时受所有层约束，全部满足才放行；
 *       旧配置未填 tiers 时，由 window/limit/dimensions 退化为每层维度一个单层配额。</li>
 *   <li>{@link #weights}：按路径前缀配置的请求权重，窗口内按权重累加消耗量判定；
 *       未匹配前缀的请求权重为 1，与旧行为一致。</li>
 * </ul>
 *
 * @param version         单调递增的策略版本号（从 1 开始）
 * @param tiers           分层配额约束（有序，判定顺序即列表顺序）
 * @param weights         路径前缀 -> 请求权重（快照内固定，热更新只对之后新窗口生效）
 * @param idempotentTtlMs 同请求标识重试复用结论的幂等窗口（毫秒）
 * @param banEnabled      是否启用封禁
 * @param banThreshold    连续超限窗口数达到该值后升级封禁
 * @param banDurations    各级封禁时长（毫秒）
 * @param banObserveMs    解封后的观察期（毫秒）
 * @param banResetObserve 观察期满且未再违规则重置级别
 * @param banDimension    封禁判定维度
 * @param timeoutMillis   存储调用超时阈值（毫秒）
 * @param failMode        降级模式
 */
public record RateLimitPolicy(
        long version,
        List<QuotaTier> tiers,
        Map<String, Long> weights,
        long idempotentTtlMs,
        boolean banEnabled,
        int banThreshold,
        List<Long> banDurations,
        long banObserveMs,
        boolean banResetObserve,
        LimitDimension banDimension,
        long timeoutMillis,
        FailMode failMode) {

    // ---- 旧访问器兼容：未使用分层配置时与改造前语义一致 ----

    /** 旧字段：封禁维度对应层的窗口长度；取不到时取第一层窗口。 */
    public long windowMillis() {
        return tiers.stream()
                .filter(t -> t.dimension() == banDimension)
                .map(QuotaTier::windowMillis)
                .findFirst()
                .orElseGet(() -> tiers.isEmpty() ? 0L : tiers.get(0).windowMillis());
    }

    /** 旧字段：封禁维度对应层的上限；取不到时取第一层上限。 */
    public long limit() {
        return tiers.stream()
                .filter(t -> t.dimension() == banDimension)
                .map(QuotaTier::limit)
                .findFirst()
                .orElseGet(() -> tiers.isEmpty() ? 0L : tiers.get(0).limit());
    }

    /** 旧字段：分层配置中出现的维度（按层级顺序去重）。 */
    public List<LimitDimension> dimensions() {
        return tiers.stream().map(QuotaTier::dimension).distinct().toList();
    }

    /**
     * 由配置构建不可变快照。
     *
     * @throws IllegalArgumentException 配置非法（窗口/阈值/封禁时长为空、权重非正等）
     */
    public static RateLimitPolicy fromProperties(long version, RateLimitProperties props) {
        RateLimitProperties.Policy p = props.getPolicy();
        RateLimitProperties.Ban b = props.getBan();
        RateLimitProperties.Storage s = props.getStorage();

        List<QuotaTier> tiers = buildTiers(p);
        Map<String, Long> weights = buildWeights(p);

        if (p.getIdempotentTtl() == null || p.getIdempotentTtl().isZero()
                || p.getIdempotentTtl().isNegative()) {
            throw new IllegalArgumentException("ratelimit.policy.idempotent-ttl must be positive");
        }
        if (b.getThresholdWindows() <= 0) {
            throw new IllegalArgumentException("ratelimit.ban.threshold-windows must be positive");
        }
        if (b.getLevelDuration() == null || b.getLevelDuration().isEmpty()) {
            throw new IllegalArgumentException("ratelimit.ban.level-duration must not be empty");
        }
        List<Long> durations = b.getLevelDuration().stream()
                .peek(d -> {
                    if (d == null || d.isZero() || d.isNegative()) {
                        throw new IllegalArgumentException("ban level duration must be positive");
                    }
                })
                .map(Duration::toMillis)
                .toList();
        if (b.getObserveWindow() == null || b.getObserveWindow().isZero() || b.getObserveWindow().isNegative()) {
            throw new IllegalArgumentException("ratelimit.ban.observe-window must be positive");
        }
        if (s.getTimeout() == null || s.getTimeout().isZero() || s.getTimeout().isNegative()) {
            throw new IllegalArgumentException("ratelimit.storage.timeout must be positive");
        }
        return new RateLimitPolicy(
                version,
                tiers,
                weights,
                p.getIdempotentTtl().toMillis(),
                b.isEnabled(),
                b.getThresholdWindows(),
                durations,
                b.getObserveWindow().toMillis(),
                b.isResetAfterObserve(),
                b.getDimension(),
                s.getTimeout().toMillis(),
                s.getFailMode() == null ? FailMode.FAIL_CLOSED : s.getFailMode());
    }

    /**
     * 构建分层配额：配置了 tiers 时按 tiers（每层独立窗口/上限）；
     * 未配置时由旧的 window/limit/dimensions 生成每层维度一个同窗口同上限的单层配额，
     * 保证旧配置行为完全不变。
     */
    private static List<QuotaTier> buildTiers(RateLimitProperties.Policy p) {
        List<RateLimitProperties.Tier> configured = p.getTiers();
        List<QuotaTier> tiers = new ArrayList<>();
        if (configured != null && !configured.isEmpty()) {
            for (int i = 0; i < configured.size(); i++) {
                RateLimitProperties.Tier t = configured.get(i);
                if (t.getDimension() == null) {
                    throw new IllegalArgumentException("tier[" + i + "].dimension must not be null");
                }
                if (t.getWindow() == null || t.getWindow().isZero() || t.getWindow().isNegative()) {
                    throw new IllegalArgumentException("tier[" + i + "].window must be positive");
                }
                if (t.getLimit() <= 0) {
                    throw new IllegalArgumentException("tier[" + i + "].limit must be positive");
                }
                tiers.add(new QuotaTier(i, t.getDimension(), t.getWindow().toMillis(), t.getLimit()));
            }
        } else {
            if (p.getWindow() == null || p.getWindow().isZero() || p.getWindow().isNegative()) {
                throw new IllegalArgumentException("ratelimit.policy.window must be positive");
            }
            if (p.getLimit() <= 0) {
                throw new IllegalArgumentException("ratelimit.policy.limit must be positive");
            }
            if (p.getDimensions() == null || p.getDimensions().isEmpty()) {
                throw new IllegalArgumentException("ratelimit.policy.dimensions must not be empty");
            }
            long window = p.getWindow().toMillis();
            int idx = 0;
            for (LimitDimension dimension : p.getDimensions()) {
                tiers.add(new QuotaTier(idx++, dimension, window, p.getLimit()));
            }
        }
        return List.copyOf(tiers);
    }

    /**
     * 构建不可变的路径前缀权重映射。键标准化（去除尾部斜杠），权重必须为正；
     * 未配置任何前缀时返回空映射，判定时请求权重一律为 1。
     */
    private static Map<String, Long> buildWeights(RateLimitProperties.Policy p) {
        Map<String, Long> source = p.getWeights();
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Long> normalized = new java.util.TreeMap<>(
                Comparator.comparingInt(String::length).reversed().thenComparing(String::compareTo));
        for (Map.Entry<String, Long> e : source.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                throw new IllegalArgumentException("weight path prefix must not be blank");
            }
            if (e.getValue() == null || e.getValue() <= 0) {
                throw new IllegalArgumentException("weight for prefix '" + e.getKey() + "' must be positive");
            }
            String prefix = e.getKey().trim();
            while (prefix.length() > 1 && prefix.endsWith("/")) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            normalized.put(prefix, e.getValue());
        }
        return Map.copyOf(normalized);
    }
}
