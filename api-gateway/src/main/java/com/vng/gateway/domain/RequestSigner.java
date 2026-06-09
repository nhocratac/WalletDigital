package com.vng.gateway.domain;

public interface RequestSigner {
    String buildCanonical(String serviceId, String method, String path, String timestamp, byte[] body);
    String sign(String secret, String canonical);
}
