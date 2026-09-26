package com.github.xingjianluo.zerotrustratelimiterjava.store;

/** Base error raised by rate-limit state stores. */
public class StoreException extends RuntimeException {
    public StoreException(String message) {
        super(message);
    }

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
