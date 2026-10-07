package com.github.xingjianluo.zerotrustratelimiterjava.model;

/**
 * 一条分层配额规则（不可变快照的一部分）。
 *
 * @param name         分层名称（拒绝原因/视图中标识被哪一层拦下）
 * @param dimension    该层的限流维度
 * @param windowMillis 该层窗口长度（毫秒）
 * @param limit        窗口内加权消耗总量上限
 * @param drivesBan    该层超限是否驱动临时封禁升级
 */
public record QuotaLayer(
        String name,
        LimitDimension dimension,
        long windowMillis,
        long limit,
        boolean drivesBan) {
}
