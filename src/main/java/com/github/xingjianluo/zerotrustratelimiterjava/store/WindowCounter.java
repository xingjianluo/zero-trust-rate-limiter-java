package com.github.xingjianluo.zerotrustratelimiterjava.store;

import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;

/**
 * 单个（版本, 分层, 维度键, 窗口起点）的加权消耗量计数器（持久化条目）。
 *
 * @param version      策略版本
 * @param layer        分层名称
 * @param dimension    维度
 * @param dimensionKey 维度键
 * @param windowStart  窗口起点（epochMillis；floor(now / 窗口长度)）
 * @param windowMillis 窗口长度（毫秒）——持久化以便重启后判断窗口是否已过及清理
 * @param consumed     窗口内加权消耗总量
 */
public record WindowCounter(
        long version,
        String layer,
        LimitDimension dimension,
        String dimensionKey,
        long windowStart,
        long windowMillis,
        long consumed) {

    public WindowCounter withConsumed(long newConsumed) {
        return new WindowCounter(version, layer, dimension, dimensionKey, windowStart, windowMillis, newConsumed);
    }
}
