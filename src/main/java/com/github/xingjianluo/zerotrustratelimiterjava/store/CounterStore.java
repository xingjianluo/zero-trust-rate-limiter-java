package com.github.xingjianluo.zerotrustratelimiterjava.store;

/**
 * Fixed-window request counter storage.
 * Keys are scoped by policy version so that historical decisions under an old
 * policy are never silently rewritten when a policy changes.
 */
public interface CounterStore {

    /** Atomically increment the counter for (policyVersion, windowStart, ruleName, source). */
    long incrementAndGet(String policyVersion, long windowStartMillis,
                         String ruleName, String source);

    long get(String policyVersion, long windowStartMillis,
             String ruleName, String source);
}
