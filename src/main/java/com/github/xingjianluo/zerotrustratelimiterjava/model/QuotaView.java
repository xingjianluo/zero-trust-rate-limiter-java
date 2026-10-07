package com.github.xingjianluo.zerotrustratelimiterjava.model;

import java.util.List;

/**
 * 按来源/维度查询的配额只读视图：各层用量与同一份封禁状态。
 *
 * @param dimension   查询维度
 * @param key         维度键
 * @param banned      当前是否封禁中
 * @param bannedUntil 封禁到期时间（epochMillis，未封禁为 0）
 * @param banLevel    封禁级别
 * @param layers      该维度下各层配额状态（按策略顺序）
 */
public record QuotaView(
        LimitDimension dimension,
        String key,
        boolean banned,
        long bannedUntil,
        int banLevel,
        List<QuotaLayerStatus> layers) {
}
