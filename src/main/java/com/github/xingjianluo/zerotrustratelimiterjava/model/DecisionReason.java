package com.github.xingjianluo.zerotrustratelimiterjava.model;

/**
 * 判定结果的原因码，保证拒绝/降级原因可区分。
 */
public enum DecisionReason {
    /** 放行。 */
    ALLOWED,
    /** 命中限流被拒。 */
    RATE_LIMITED,
    /** 来源处于临时封禁期。 */
    BANNED,
    /** 降级：失败关闭。 */
    FAIL_CLOSED,
    /** 降级：失败开放（放行但显式标记）。 */
    FAIL_OPEN
}
