package com.github.xingjianluo.zerotrustratelimiterjava.model;

/**
 * 单条请求权重配置：匹配路径的请求每次消耗 {@code weight} 单位配额。
 *
 * @param path   路径匹配规则（精确路径优先，其次最长前缀）
 * @param weight 每次请求的配额消耗量（正整数；未命中任何规则时按 1）
 */
public record WeightRule(String path, long weight) {
}
