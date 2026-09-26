package com.github.xingjianluo.zerotrustratelimiterjava.core;

/** Default wall-clock implementation. */
public class SystemTimeSource implements TimeSource {
    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }

    @Override
    public long nanoTime() {
        return System.nanoTime();
    }
}
