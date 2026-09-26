package com.github.xingjianluo.zerotrustratelimiterjava.store;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime-toggleable fault injection used for local verification of degradation paths.
 * Toggled through the admin API; no external service required.
 */
public class FaultState {

    private volatile boolean unavailable;
    private final AtomicLong forcedDelayMillis = new AtomicLong(0);

    public boolean isUnavailable() {
        return unavailable;
    }

    public void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
    }

    public long getForcedDelayMillis() {
        return forcedDelayMillis.get();
    }

    public void setForcedDelayMillis(long millis) {
        this.forcedDelayMillis.set(Math.max(0L, millis));
    }

    public void reset() {
        this.unavailable = false;
        this.forcedDelayMillis.set(0L);
    }
}
