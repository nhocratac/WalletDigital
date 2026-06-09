package com.vng.gateway.domain;

/** Kết quả bóc từ JWT — thuần Java, không Spring/JPA. */
public record AuthenticatedCaller(String userId, String tenantId) {
}
