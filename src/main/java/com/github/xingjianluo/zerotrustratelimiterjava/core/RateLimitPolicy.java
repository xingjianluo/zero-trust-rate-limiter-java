package com.github.xingjianluo.zerotrustratelimiterjava.core;

import com.github.xingjianluo.zerotrustratelimiterjava.config.RateLimitProperties;
import com.github.xingjianluo.zerotrustratelimiterjava.model.FailMode;
import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;

import java.time.Duration;
import java.util.List;

/**
 * 不可变策略快照。每次热更新生成新快照与新版本号；判定过程只引用单一快照，
 * 保证一次请求不会混用新旧策略，历史判定也不会被静默改写。
 *
 * @param version        单调递增的策略版本号（从 1 开始）
 * @param windowMillis   限流窗口长度（毫秒）
 * @param limit          窗口内最大请求数
 * @param dimensions     限流维度（顺序即判定顺序）
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
        long windowMillis,
        long limit,
        List<LimitDimension> dimensions,
        boolean banEnabled,
        int banThreshold,
        List<Long> banDurations,
        long banObserveMs,
        boolean banResetObserve,
        LimitDimension banDimension,
        long timeoutMillis,
        FailMode failMode) {

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
        if (p.getLimit() <= 0) {
            throw new IllegalArgumentException("ratelimit.policy.limit must be positive");
        }
        if (p.getDimensions() == null || p.getDimensions().isEmpty()) {
            throw new IllegalArgumentException("ratelimit.policy.dimensions must not be empty");
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
                p.getWindow().toMillis(),
                p.getLimit(),
                List.copyOf(p.getDimensions()),
                b.isEnabled(),
                b.getThresholdWindows(),
                durations,
                b.getObserveWindow().toMillis(),
                b.isResetAfterObserve(),
                b.getDimension(),
                s.getTimeout().toMillis(),
                s.getFailMode() == null ? FailMode.FAIL_CLOSED : s.getFailMode());
    }
}
