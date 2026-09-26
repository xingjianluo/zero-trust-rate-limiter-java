package com.github.xingjianluo.zerotrustratelimiterjava.model;

/** Immutable snapshot of an entry request used as decision input. */
public record RequestEntry(
        String requestId,
        String clientIp,
        String path,
        String method,
        String userId,
        String token) {

    private static final String UNKNOWN = "unknown";

    /**
     * Extract the identity value for the configured dimension. Values are stable strings
     * so they can be used directly as counter/ban keys.
     */
    public String dimensionValue(Dimension dimension) {
        return switch (dimension) {
            case IP -> nz(clientIp);
            case USER -> nz(userId);
            case TOKEN -> nz(token);
            case PATH -> path == null ? "/" : path;
            case IP_PATH -> nz(clientIp) + "|" + (path == null ? "/" : path);
        };
    }

    private static String nz(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value;
    }
}
