package com.github.xingjianluo.zerotrustratelimiterjava.core;

import com.github.xingjianluo.zerotrustratelimiterjava.config.RateLimitProperties;
import com.github.xingjianluo.zerotrustratelimiterjava.model.FailMode;
import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;
import com.github.xingjianluo.zerotrustratelimiterjava.model.QuotaLayer;
import com.github.xingjianluo.zerotrustratelimiterjava.model.WeightRule;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 不可变策略快照。每次热更新生成新快照与新版本号；判定过程只引用单一快照，
 * 保证一次请求不会混用新旧策略，历史判定也不会被静默改写。
 *
 * <p>配额模型：
 * <ul>
 *   <li>{@code layers}：分层配额，每层有自己的维度、窗口长度与加权消耗上限；
 *       一次请求必须通过所有适用层才放行。未显式配置 layers 时，由旧版
 *       {@code window/limit/dimensions} 为每个维度派生一层，行为与旧版完全一致。</li>
 *   <li>{@code weights}：按路径配置的单请求权重；未命中规则权重为 1。
 *       权重只影响其生效之后新开始窗口里的新请求——窗口用量按请求时刻的权重累加，
 *       且窗口计数键含策略版本，热更新不改写既有窗口的历史用量与判定。</li>
 * </ul>
 *
 * @param version        单调递增的策略版本号（从 1 开始）
 * @param layers         分层配额（顺序即判定顺序）
 * @param weights        请求权重规则（可为空，表示全部按 1）
 * @param banEnabled     是否启用封禁
 * @param banThreshold   连续超限窗口数达到该值后升级封禁
 * @param banDurations   各级封禁时长（毫秒）
 * @param banObserveMs   解封后的观察期（毫秒）
 * @param banResetObserve 观察期满且未再违规则重置级别
 * @param banDimension   封禁判定维度
 * @param timeoutMillis  存储调用超时阈值（毫秒）
 * @param failMode       降级模式
 */
public record RateLimitPolicy(
        long version,
        List<QuotaLayer> layers,
        List<WeightRule> weights,
        boolean banEnabled,
        int banThreshold,
        List<Long> banDurations,
        long banObserveMs,
        boolean banResetObserve,
        LimitDimension banDimension,
        long timeoutMillis,
        FailMode failMode) {

    /** 旧版单窗口长度：取所有层中最短的窗口长度（封禁连续性等复用语义）。 */
    public long windowMillis() {
        return layers.stream().mapToLong(QuotaLayer::windowMillis).min().orElse(1000L);
    }

    /** 旧版单上限（测试/兼容辅助）：取第一层上限。 */
    public long limit() {
        return layers.isEmpty() ? 0L : layers.get(0).limit();
    }

    /** 旧版维度列表（测试/兼容辅助）。 */
    public List<LimitDimension> dimensions() {
        return layers.stream().map(QuotaLayer::dimension).distinct().toList();
    }

    /** 返回会驱动封禁升级的分层（按顺序）。 */
    public List<QuotaLayer> banDrivingLayers() {
        return layers.stream().filter(QuotaLayer::drivesBan).toList();
    }

    /**
     * 由配置构建不可变快照。
     *
     * @throws IllegalArgumentException 配置非法（窗口/阈值/封禁时长为空等）
     */
    public static RateLimitPolicy fromProperties(long version, RateLimitProperties props) {
        RateLimitProperties.Policy p = props.getPolicy();
        RateLimitProperties.Ban b = props.getBan();
        RateLimitProperties.Storage s = props.getStorage();

        if (p.getWindow() == null || p.getWindow().isZero() || p.getWindow().isNegative()) {
            throw new IllegalArgumentException("ratelimit.policy.window must be positive");
        }
        if (p.getDimensions() == null || p.getDimensions().isEmpty()) {
            throw new IllegalArgumentException("ratelimit.policy.dimensions must not be empty");
        }
        List<QuotaLayer> layers = resolveLayers(p, b.getDimension());
        if (layers.isEmpty()) {
            throw new IllegalArgumentException("ratelimit.policy.layers must not be empty");
        }
        List<WeightRule> weights = resolveWeights(p);

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
                List.copyOf(layers),
                List.copyOf(weights),
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
     * 分层解析：显式配置 layers 时逐层校验并补默认值；否则由旧版配置为每个维度派生一层
     * （窗口=window，上限=limit，仅封禁维度那一层 drivesBan），保证旧配置行为不变。
     */
    private static List<QuotaLayer> resolveLayers(RateLimitProperties.Policy p, LimitDimension banDimension) {
        List<QuotaLayer> out = new ArrayList<>();
        if (p.getLayers() != null && !p.getLayers().isEmpty()) {
            int autoIdx = 0;
            for (RateLimitProperties.Layer lc : p.getLayers()) {
                if (lc.getDimension() == null) {
                    throw new IllegalArgumentException("ratelimit.policy.layers[].dimension is required");
                }
                if (lc.getLimit() <= 0) {
                    throw new IllegalArgumentException("ratelimit.policy.layers[].limit must be positive");
                }
                Duration window = lc.getWindow() != null ? lc.getWindow() : p.getWindow();
                if (window == null || window.isZero() || window.isNegative()) {
                    throw new IllegalArgumentException("ratelimit.policy.layers[].window must be positive");
                }
                String name = (lc.getName() == null || lc.getName().isBlank())
                        ? lc.getDimension().name().toLowerCase() + "-" + (++autoIdx)
                        : lc.getName();
                boolean drivesBan = lc.getDrivesBan() != null
                        ? lc.getDrivesBan()
                        : lc.getDimension() == banDimension;
                out.add(new QuotaLayer(name, lc.getDimension(), window.toMillis(), lc.getLimit(), drivesBan));
            }
            return out;
        }
        // 旧配置兼容路径
        if (p.getLimit() <= 0) {
            throw new IllegalArgumentException("ratelimit.policy.limit must be positive");
        }
        long windowMs = p.getWindow().toMillis();
        int idx = 0;
        for (LimitDimension dimension : p.getDimensions()) {
            idx++;
            String name = dimension.name().toLowerCase() + "-" + idx;
            out.add(new QuotaLayer(name, dimension, windowMs, p.getLimit(),
                    dimension == banDimension));
        }
        return out;
    }

    /** 权重规则校验：路径非空、权重为正整数。 */
    private static List<WeightRule> resolveWeights(RateLimitProperties.Policy p) {
        List<WeightRule> out = new ArrayList<>();
        if (p.getWeights() == null) {
            return out;
        }
        for (RateLimitProperties.WeightRule wc : p.getWeights()) {
            if (wc.getPath() == null || wc.getPath().isBlank()) {
                throw new IllegalArgumentException("ratelimit.policy.weights[].path must not be blank");
            }
            if (wc.getWeight() <= 0) {
                throw new IllegalArgumentException("ratelimit.policy.weights[].weight must be positive");
            }
            out.add(new WeightRule(wc.getPath(), wc.getWeight()));
        }
        return out;
    }
}
