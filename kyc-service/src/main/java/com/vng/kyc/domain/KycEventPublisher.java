package com.vng.kyc.domain;

/** PORT — phát event nghiệp vụ. SP2: adapter log; SP3: Kafka. */
public interface KycEventPublisher {
    void publishKycRevoked(String userId, String reason);
}
