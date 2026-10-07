package com.github.xingjianluo.zerotrustratelimiterjava.core;

import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;

/**
 * 不可变的单层配额约束（策略快照的一部分）。
 *
 * <p>同一请求可能同时受多层约束（如来源短窗口、来源长窗口、全局总量），
 * 每一层有独立的窗口长度与上限，只有所有层都满足时才放行。
 *
 * @param index        该层在策略中的序号（从 0 开始，用于拒绝信息与视图中指明“被哪一层拦下”）
 * @param dimension    该层约束的维度
 * @param windowMillis 该层固定窗口长度（毫秒）
 * @param limit        该层窗口内允许的最大累计消耗量（按请求权重累加，而非请求条数）
 */
public record QuotaTier(int index,
                        LimitDimension dimension,
                        long windowMillis,
                        long limit) {

    /** 该层的可读名称，用于拒绝原因与查询视图，例如 {@code tier[0]:IP:1000ms}。 */
    public String name() {
        return "tier[" + index + "]:" + dimension.name() + ":" + windowMillis + "ms";
    }
}
