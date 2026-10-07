package com.github.xingjianluo.zerotrustratelimiterjava.store;

import com.github.xingjianluo.zerotrustratelimiterjava.model.LimitDimension;

import java.util.List;

/**
 * 配额计数存储。
 *
 * <p>计数键包含层维度与窗口起点，窗口切换自然产生新键；键中带策略版本，
 * 热更新后旧版本计数保留但不再参与判定，历史判定不被改写。
 *
 * <p>计数单位是“消耗量”而非请求条数：一次请求按其权重（未配置权重时为 1）原子累加，
 * 窗口内按实际累加的消耗量判断是否超限。
 */
public interface RateLimitStateStore {

    /**
     * 原子地尝试预留配额：仅当该窗口当前消耗量 + {@code weight} 不超过 {@code limit} 时才累加，
     * 并返回预留后的消耗量；若会超限则保持计数不变并返回 -1。
     *
     * <p>用于分层配额的“所有层都满足才放行”：编排层对各层依次预留，任一层返回 -1 则回滚
     * 已预留层并整体拒绝，保证被拒请求不消耗任何一层的配额。
     *
     * @param limit 该层窗口上限
     * @return 预留成功后的窗口内消耗量；被拒（会超限）为 -1
     */
    long tryConsume(long version, LimitDimension dimension, String dimensionKey,
                    long windowMillis, long windowStart, long weight, long limit);

    /**
     * 回滚一次此前预留成功的配额（同一请求在更后层被拒时，归还先前层已预留的量）。
     */
    void refund(long version, LimitDimension dimension, String dimensionKey,
                long windowMillis, long windowStart, long weight);

    /**
     * 只读快照：指定版本下，给定维度 + 维度键在各候选窗口起点上的当前消耗量。
     * 视图与入口判定读取同一份内存状态。不存在的窗口起点不返回。
     *
     * @param version      策略版本
     * @param dimension    维度
     * @param dimensionKey 维度键值
     * @param windowStarts 关心的窗口起点（通常是各层当前窗口起点）
     * @return 命中的计数器快照（未命中的窗口起点被省略）
     */
    List<CounterSnapshot> snapshot(long version, LimitDimension dimension, String dimensionKey,
                                   long windowMillis, List<Long> windowStarts);

    /**
     * 丢弃早于指定窗口起点的计数（内存实现用于控制增长）。
     */
    void pruneOlderThan(long minWindowStart);

    /** 一个计数器的只读快照。 */
    record CounterSnapshot(long version,
                           LimitDimension dimension,
                           String dimensionKey,
                           long windowMillis,
                           long windowStart,
                           long used) {
    }
}
