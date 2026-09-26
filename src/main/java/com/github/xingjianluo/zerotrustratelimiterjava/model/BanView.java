package com.github.xingjianluo.zerotrustratelimiterjava.model;

/**
 * 管理端查询封禁列表的返回项（视图）。
 *
 * @param key        来源键
 * @param level      当前级别
 * @param banned     当前是否处于封禁中
 * @param bannedUntil 封禁到期时间（epochMillis，未封禁为 0）
 * @param consecutiveLimitedWindows 连续超限窗口数
 * @param releasedAt 最近解封时间
 */
public record BanView(
        String key,
        int level,
        boolean banned,
        long bannedUntil,
        int consecutiveLimitedWindows,
        long releasedAt) {

    /** 由领域记录构造视图，按当前时间计算是否仍在封禁中。 */
    public static BanView from(BanRecord record, long nowMillis) {
        return new BanView(
                record.key(),
                record.level(),
                record.bannedUntil() > nowMillis,
                record.bannedUntil(),
                record.consecutiveLimitedWindows(),
                record.releasedAt());
    }
}
