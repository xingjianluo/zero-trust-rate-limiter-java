package com.github.xingjianluo.zerotrustratelimiterjava.store;

import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;

/**
 * 限流计数存储。
 *
 * <p>计数键包含维度与窗口起点，窗口切换自然产生新键；键中带策略版本，
 * 热更新后旧版本计数保留但不再参与判定，历史判定不被改写。
 */
public interface RateLimitStateStore {

    /**
     * 对指定维度键在指定窗口内原子自增并返回自增后的值。
     *
     * @param version     策略版本（参与键构成）
     * @param dimension   限流维度
     * @param dimensionKey 维度键值
     * @param windowStart 窗口起点（epochMillis）
     * @return 自增后的窗口内计数
     */
    long incrementAndGet(long version, LimitDimension dimension, String dimensionKey, long windowStart);

    /**
     * 丢弃早于指定窗口起点的计数（内存实现用于控制增长）。
     */
    void pruneOlderThan(long minWindowStart);
}
