package com.github.xingjianluo.zerotrustratelimiterjava.model;

/**
 * 限流维度。键由 {@code com.github.xingjianluo.zerotrustratelimiterjava.core.KeyExtractor} 提取。
 */
public enum LimitDimension {
    IP,
    USER,
    PATH,
    GLOBAL
}
