package com.github.xingjianluo.zerotrustratelimiterjava.core;

/**
 * 可替换时钟，便于测试窗口切换与封禁到期。
 */
public interface Clock {
    /** 当前 epoch 毫秒。 */
    long nowMillis();
}
