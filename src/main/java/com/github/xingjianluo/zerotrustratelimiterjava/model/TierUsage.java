package com.github.xingjianluo.zerotrustratelimiterjava.model;

/**
 * 单层配额在当前窗口内的用量只读视图。
 *
 * @param tierIndex     该层在策略中的序号
 * @param tierName      该层可读名称（tier[i]:DIMENSION:windowMs）
 * @param dimension     该层维度
 * @param windowMillis  该层窗口长度（毫秒）
 * @param windowStart   当前窗口起点（epochMillis；当前窗口无计数时为 -1）
 * @param limit         该层窗口内上限（累计消耗量）
 * @param used          当前窗口已累计消耗量
 * @param remaining     当前窗口剩余额度（limit - used，不会为负）
 */
public record TierUsage(int tierIndex,
                        String tierName,
                        LimitDimension dimension,
                        long windowMillis,
                        long windowStart,
                        long limit,
                        long used,
                        long remaining) {
}
