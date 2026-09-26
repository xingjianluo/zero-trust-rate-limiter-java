package com.github.xingjianluo.zerotrustratelimiterjava.core;

import org.springframework.stereotype.Component;

/** 系统墙钟。 */
@Component
public class SystemClock implements Clock {
    @Override
    public long nowMillis() {
        return System.currentTimeMillis();
    }
}
