package com.github.xingjianluo.zerotrustratelimiterjava.model;

/**
 * 一次请求的最终判定结果（不可变）。
 *
 * <p>分层配额下被拒时，{@code limitedByTier} 等字段明确指出被哪一层拦下、该层上限、
 * 当前窗口已用量与剩余额度；未命中具体层（封禁/降级）时层相关字段为 -1 或 null。
 *
 * @param requestId          请求标识
 * @param allowed            是否放行
 * @param reason             原因码
 * @param detail             人类可读说明（含触发层/维度/上限/已用/剩余等）
 * @param policyVersion      判定时使用的策略版本
 * @param windowStart        命中的限流窗口起点（epochMillis，降级/封禁时为 -1）
 * @param currentCount       命中层在窗口内的累计消耗量（降级/封禁时为 -1）
 * @param limitedBy          触发限流的维度（仅 RATE_LIMITED 时非空）
 * @param banLevel           封禁级别（仅 BANNED 时有值，否则 0）
 * @param banUntil           封禁到期时间（epochMillis，无封禁为 -1）
 * @param degraded           是否发生了降级
 * @param degradedCause      降级的具体原因（STORE_UNAVAILABLE / STORE_TIMEOUT），未降级为 null
 * @param retriedAfterMillis 建议的 Retry-After（毫秒，无则 -1）
 * @param requestWeight      本次请求按权重计算的配额消耗量（未配置权重为 1）
 * @param limitedByTier      被哪一层配额拦下（层序号，从 0 开始；非 RATE_LIMITED 为 -1）
 * @param tierName           命中层可读名称（如 tier[0]:IP:1000ms；非 RATE_LIMITED 为 null）
 * @param tierLimit          命中层窗口上限（非 RATE_LIMITED 为 -1）
 * @param tierRemaining      命中层窗口剩余额度（非 RATE_LIMITED 为 -1）
 * @param replayed           是否为重试幂等命中（复用了首次结论，未访问存储）
 */
public record Decision(
        String requestId,
        boolean allowed,
        DecisionReason reason,
        String detail,
        long policyVersion,
        long windowStart,
        long currentCount,
        LimitDimension limitedBy,
        int banLevel,
        long banUntil,
        boolean degraded,
        String degradedCause,
        long retriedAfterMillis,
        long requestWeight,
        int limitedByTier,
        String tierName,
        long tierLimit,
        long tierRemaining,
        boolean replayed) {

    /** 兼容旧调用方的 13 参构造：层相关字段取默认值，权重 1、非重试。 */
    public Decision(String requestId, boolean allowed, DecisionReason reason, String detail,
                    long policyVersion, long windowStart, long currentCount,
                    LimitDimension limitedBy, int banLevel, long banUntil,
                    boolean degraded, String degradedCause, long retriedAfterMillis) {
        this(requestId, allowed, reason, detail, policyVersion, windowStart, currentCount,
                limitedBy, banLevel, banUntil, degraded, degradedCause, retriedAfterMillis,
                1L, -1, null, -1L, -1L, false);
    }
}
