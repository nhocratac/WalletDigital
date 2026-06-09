package com.vng.gateway.domain;

/** Lỗi khi gọi downstream. type quyết định gateway trả 502 hay 504. */
public class DownstreamException extends RuntimeException {

    public enum Type { UPSTREAM_5XX, TIMEOUT }

    private final Type type;

    public DownstreamException(Type type, String message) {
        super(message);
        this.type = type;
    }

    public Type getType() {
        return type;
    }
}
