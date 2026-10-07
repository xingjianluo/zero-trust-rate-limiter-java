package com.github.xingjianluo.zerotrustratelimiterjava.model;

/**
 * 只读视图中单条分层配额的当前状态。
 *
 * @param name        分层名称
 * @param dimension   维度
 * @param windowStart 当前窗口起点（epochMillis）
 * @param windowMillis 窗口长度（毫秒）
 * @param limit       本层上限（加权消耗量）
 * @param used        当前窗口已用量（加权累加）
 * @param remaining   剩余额度（limit - used，不小于 0）
 */
public record QuotaLayerStatus(
        String name,
        LimitDimension dimension,
        long windowStart,
        long windowMillis,
        long limit,
        long used,
        long remaining) {
}
