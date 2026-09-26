package com.github.xingjianluo.zerotrustratelimiterjava.store;

/**
 * 存储层异常的统一基类，异常链路通过 {@link #getKind()} 区分原因。
 */
public class StoreException extends RuntimeException {

    /** 异常种类。 */
    public enum Kind {
        /** 存储不可用（IO/持久化失败）。 */
        STORE_UNAVAILABLE,
        /** 存储调用超时（延迟超阈值）。 */
        STORE_TIMEOUT
    }

    private final Kind kind;

    public StoreException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }
}
