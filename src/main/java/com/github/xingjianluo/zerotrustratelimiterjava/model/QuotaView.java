package com.github.xingjianluo.zerotrustratelimiterjava.model;

import java.util.List;

/**
 * 按来源（维度 + 维度键）聚合的配额只读视图：包含该来源相关的各层当前用量、
 * 剩余额度，以及是否处于封禁中。视图数值与入口判定读取的是同一份内存计数状态。
 *
 * @param dimension   查询维度
 * @param key         维度键（如 IP）
 * @param banned      当前是否处于封禁中
 * @param banLevel    封禁级别（未封禁为 0）
 * @param banUntil    封禁到期时间（epochMillis；未封禁为 0）
 * @param policyVersion 视图数据对应的策略版本
 * @param tiers       该维度相关各层的当前用量
 */
public record QuotaView(LimitDimension dimension,
                        String key,
                        boolean banned,
                        int banLevel,
                        long banUntil,
                        long policyVersion,
                        List<TierUsage> tiers) {
}
