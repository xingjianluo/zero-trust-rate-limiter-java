package com.github.xingjianluo.zerotrustratelimiterjava.tests;

import com.github.xingjianluo.zerotrustratelimiterjava.core.Clock;

/** 可手动设置/推进的时钟，供窗口切换与封禁到期测试使用。 */
public class ManualClock implements Clock {

    private volatile long now;

    public ManualClock(long startMillis) {
        this.now = startMillis;
    }

    @Override
    public long nowMillis() {
        return now;
    }

    public void set(long millis) {
        this.now = millis;
    }

    public void advance(long deltaMillis) {
        this.now += deltaMillis;
    }
}
