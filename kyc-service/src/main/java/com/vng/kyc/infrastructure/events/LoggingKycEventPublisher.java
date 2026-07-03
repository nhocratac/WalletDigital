package com.vng.kyc.infrastructure.events;

import com.vng.kyc.domain.KycEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** ADAPTER no-op/log cho SP2. SP3+: OutboxKycEventPublisher thay thế khi bật cờ kafka-enabled (O1). */
@Component
@ConditionalOnProperty(name = "kyc.events.kafka-enabled", havingValue = "false", matchIfMissing = true)
public class LoggingKycEventPublisher implements KycEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingKycEventPublisher.class);

    @Override
    public void publishKycRevoked(String userId, String reason) {
        log.info("EVENT kyc.revoked userId={} reason={}", userId, reason);
    }
}
