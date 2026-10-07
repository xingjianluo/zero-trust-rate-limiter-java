package com.github.xingjianluo.zerotrustratelimiterjava.model;

/**
 * 一次请求的最终判定结果（不可变）。
 *
 * @param requestId          请求标识
 * @param allowed            是否放行
 * @param reason             原因码
 * @param detail             人类可读说明（含触发分层/上限/用量等）
 * @param policyVersion      判定时使用的策略版本
 * @param windowStart        命中分层的窗口起点（epochMillis，降级时为 -1）
 * @param currentCount       命中分层窗口内的加权消耗总量（降级时为 -1）
 * @param limitedBy          触发限流的维度（仅 RATE_LIMITED 时非空）
 * @param banLevel           封禁级别（仅 BANNED 时有值，否则 0）
 * @param banUntil           封禁到期时间（epochMillis，无封禁为 -1）
 * @param degraded           是否发生了降级
 * @param degradedCause      降级的具体原因（STORE_UNAVAILABLE / STORE_TIMEOUT），未降级为 null
 * @param retriedAfterMillis 建议的 Retry-After（毫秒，无则 -1）
 * @param layerName          拦下请求的分层名称（仅 RATE_LIMITED 时有值）
 * @param layerLimit         拦下分层的上限
 * @param remaining          拦下分层的剩余额度（非负）
 * @param weight             本次请求实际使用的权重（降级时为 -1）
 * @param replay             是否为幂等缓存命中（重复请求直接返回首次结论）
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
        String layerName,
        long layerLimit,
        long remaining,
        long weight,
        boolean replay) {

    /** 兼容旧调用点的 13 参构造：分层/权重相关字段给默认值。 */
    public Decision(String requestId, boolean allowed, DecisionReason reason, String detail,
                    long policyVersion, long windowStart, long currentCount,
                    LimitDimension limitedBy, int banLevel, long banUntil,
                    boolean degraded, String degradedCause, long retriedAfterMillis) {
        this(requestId, allowed, reason, detail, policyVersion, windowStart, currentCount,
                limitedBy, banLevel, banUntil, degraded, degradedCause, retriedAfterMillis,
                limitedBy == null ? null : limitedBy.name().toLowerCase(),
                -1L, -1L, -1L, false);
    }
}
