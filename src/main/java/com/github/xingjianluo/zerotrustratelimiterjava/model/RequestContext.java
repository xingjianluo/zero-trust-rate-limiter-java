package com.github.xingjianluo.zerotrustratelimiterjava.model;

/**
 * 一次入口请求的判定输入。
 *
 * @param requestId 请求唯一标识（贯穿日志与响应头）
 * @param ip        来源 IP
 * @param user      已认证主体标识（未认证为 {@code null}）
 * @param path      请求路径
 * @param method    HTTP 方法
 */
public record RequestContext(
        String requestId,
        String ip,
        String user,
        String path,
        String method) {
}
