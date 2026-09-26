package com.github.xingjianluo.zerotrustratelimiterjava.core;

import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;
import com.github.xingjianluo.zerotrustratelimiterjava.model.RequestContext;
import org.springframework.stereotype.Component;

/**
 * 按维度从请求中提取限流键。
 *
 * <ul>
 *   <li>{@link LimitDimension#IP}：来源 IP；</li>
 *   <li>{@link LimitDimension#USER}：已认证主体标识（{@code X-User-Id} 头），未认证返回 null（跳过该维度）；</li>
 *   <li>{@link LimitDimension#PATH}：请求路径；</li>
 *   <li>{@link LimitDimension#GLOBAL}：全局单一键。</li>
 * </ul>
 */
@Component
public class KeyExtractor {

    /** USER 维度缺失时跳过该维度而不是把所有匿名用户聚到同一键。 */
    public String extract(LimitDimension dimension, RequestContext request) {
        return switch (dimension) {
            case IP -> request.ip() == null || request.ip().isBlank() ? null : request.ip();
            case USER -> request.user() == null || request.user().isBlank() ? null : request.user();
            case PATH -> request.path();
            case GLOBAL -> "global";
        };
    }
}
