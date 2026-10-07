package com.github.xingjianluo.zerotrustratelimiterjava.store;

import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;
import com.github.xingjianluo.zerotrustratelimiterjava.model.QuotaLayer;

import java.util.List;
import java.util.Optional;

/**
 * 限流/配额计数存储。
 *
 * <p>计数键包含策略版本、分层、维度与窗口起点，窗口切换自然产生新键；
 * 热更新后旧版本计数保留但不再参与判定，历史判定不被改写。
 */
public interface RateLimitStateStore {

    /**
     * 对指定维度键在指定窗口内原子自增 1（无权重场景，等价权重 1）并返回新值。
     */
    long incrementAndGet(long version, LimitDimension dimension, String dimensionKey, long windowStart);

    /**
     * 加权消耗：原子累加 {@code weight} 并返回累加后的窗口消耗总量。
     *
     * @param version      策略版本
     * @param layer        命中的分层配额
     * @param dimensionKey 维度键
     * @param windowStart  窗口起点（epochMillis）
     * @param weight       本请求在该层的消耗量
     * @return 累加后的窗口内总消耗
     */
    default long consumeAndGet(long version, QuotaLayer layer, String dimensionKey,
                               long windowStart, long weight) {
        throw new UnsupportedOperationException("consumeAndGet not implemented");
    }

    /** 读取指定计数器（只读视图用）；不存在返回 empty。 */
    default Optional<WindowCounter> find(long version, String layer, LimitDimension dimension,
                                         String dimensionKey, long windowStart) {
        throw new UnsupportedOperationException("find not implemented");
    }

    /** 列出当前仍可能被引用的计数器（窗口未过），供只读视图使用。 */
    default List<WindowCounter> findActive(long nowMillis) {
        throw new UnsupportedOperationException("findActive not implemented");
    }

    /**
     * 丢弃早于指定窗口起点的计数（内存实现用于控制增长）。
     */
    void pruneOlderThan(long minWindowStart);
}
