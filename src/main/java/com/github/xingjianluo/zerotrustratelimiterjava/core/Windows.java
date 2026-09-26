package com.github.xingjianluo.zerotrustratelimiterjava.core;

/** Fixed-window boundary arithmetic. Windows are aligned to the epoch. */
public final class Windows {
    private Windows() {
    }

    /**
     * Inclusive start timestamp of the fixed window containing {@code nowMillis}.
     *
     * @param nowMillis     current instant in milliseconds
     * @param windowSeconds window length in seconds (must be positive)
     */
    public static long windowStart(long nowMillis, long windowSeconds) {
        long windowMillis = Math.multiplyExact(windowSeconds, 1000L);
        return Math.floorDiv(nowMillis, windowMillis) * windowMillis;
    }
}
