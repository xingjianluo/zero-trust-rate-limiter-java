package com.github.xingjianluo.zerotrustratelimiterjava.model;

/**
 * 单个来源（封禁维度键）的封禁状态，持久化到文件以跨重启保持一致语义。
 *
 * <p>升级规则：{@code consecutiveLimitedWindows} 达到阈值 → 封禁级别 +1；
 * 解封后观察期内再次超限 → 级别再 +1；观察期满未违规 → 级别与计数清零。
 *
 * @param key                      来源键（如 ip）
 * @param level                    当前封禁级别（0 表示未封禁）
 * @param bannedUntil              当前封禁到期时间（epochMillis；未封禁为 0）
 * @param banDurationMillis        当前封禁采用的时长（毫秒）——取封禁生成时的策略快照，
 *                                 之后策略调整不改变既有封禁，保证语义稳定
 * @param consecutiveLimitedWindows 连续超限窗口计数
 * @param lastLimitedWindowStart   最近一次超限所在窗口起点（epochMillis）
 * @param releasedAt               最近一次解封时间（epochMillis；从未解封为 0）
 * @param policyVersionAtBan       触发当前封禁时的策略版本
 * @param updatedAt                记录最后更新时间（epochMillis）
 */
public record BanRecord(
        String key,
        int level,
        long bannedUntil,
        long banDurationMillis,
        int consecutiveLimitedWindows,
        long lastLimitedWindowStart,
        long releasedAt,
        long policyVersionAtBan,
        long updatedAt) {

    /** 为指定键创建初始空记录。 */
    public static BanRecord empty(String key) {
        return new BanRecord(key, 0, 0L, 0L, 0, 0L, 0L, 0L, 0L);
    }
}
