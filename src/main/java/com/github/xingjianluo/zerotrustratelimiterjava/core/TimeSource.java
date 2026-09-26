package com.github.xingjianluo.zerotrustratelimiterjava.core;

/** Injectable clock so window switching and ban expiry are deterministic in tests. */
public interface TimeSource {
    long currentTimeMillis();

    long nanoTime();
}
