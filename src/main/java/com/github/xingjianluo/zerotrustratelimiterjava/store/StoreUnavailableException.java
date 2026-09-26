package com.github.xingjianluo.zerotrustratelimiterjava.store;

/** The store is unavailable (injected fault or I/O error). Distinguishable from timeout. */
public class StoreUnavailableException extends StoreException {
    public StoreUnavailableException(String message) {
        super(message);
    }

    public StoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
