package com.github.xingjianluo.zerotrustratelimiterjava.store;

/** The store did not respond within the configured latency threshold. */
public class StoreTimeoutException extends StoreException {
    public StoreTimeoutException(String message) {
        super(message);
    }
}
