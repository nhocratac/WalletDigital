package com.vng.gateway.domain;

/** PORT: xác minh một token và trả về danh tính, hoặc ném InvalidTokenException. */
public interface TokenVerifier {
    AuthenticatedCaller verify(String token);
}
