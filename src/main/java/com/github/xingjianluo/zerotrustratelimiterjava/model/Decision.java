package com.github.xingjianluo.zerotrustratelimiterjava.model;

/**
 * 一次请求的最终判定结果（不可变）。
 *
 * @param requestId          请求标识
 * @param allowed            是否放行
 * @param reason             原因码
 * @param detail             人类可读说明（含触发维度/阈值等）
 * @param policyVersion      判定时使用的策略版本
 * @param windowStart        命中的限流窗口起点（epochMillis，降级时为 -1）
 * @param currentCount       命中维度在窗口内的计数（降级时为 -1）
 * @param limitedBy          触发限流的维度（仅 RATE_LIMITED 时非空）
 * @param banLevel           封禁级别（仅 BANNED 时有值，否则 0）
 * @param banUntil           封禁到期时间（epochMillis，无封禁为 -1）
 * @param degraded           是否发生了降级
 * @param degradedCause      降级的具体原因（STORE_UNAVAILABLE / STORE_TIMEOUT），未降级为 null
 * @param retriedAfterMillis 建议的 Retry-After（毫秒，无则 -1）
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
        long retriedAfterMillis) {
}
