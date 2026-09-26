package com.github.xingjianluo.zerotrustratelimiterjava.model;

/**
 * 存储不可用或处理延迟超阈值时入口的降级策略。
 */
public enum FailMode {
    /** 失败关闭：拒绝请求（HTTP 503）。 */
    FAIL_CLOSED,
    /** 失败开放：放行请求，但通过日志/响应头标记降级，不静默放行。 */
    FAIL_OPEN
}
