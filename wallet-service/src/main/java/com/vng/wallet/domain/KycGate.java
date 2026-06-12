package com.vng.wallet.domain;

/** PORT: cổng KYC. Adapter quyết định bằng cache/REST/breaker — domain không biết. */
public interface KycGate {
    enum Decision { ALLOWED, DENIED, UNAVAILABLE }
    record KycCheckResult(Decision decision, String kycStatus) {}
    KycCheckResult check(String userId);
}
