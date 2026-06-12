package com.vng.kyc.infrastructure.events;

import com.vng.kyc.domain.KycEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** ADAPTER Kafka thay Logging khi bật cờ — domain/application KHÔNG đổi (port trả công). */
@Component
@ConditionalOnProperty(name = "kyc.events.kafka-enabled", havingValue = "true")
public class KafkaKycEventPublisher implements KycEventPublisher {

    public static final String TOPIC = "kyc.revoked";

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaKycEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    @Override
    public void publishKycRevoked(String userId, String reason) {
        String payload = "{\"userId\":\"" + userId + "\",\"reason\":\"" + reason.replace("\"", "'")
                + "\",\"revokedAt\":\"" + Instant.now() + "\"}";
        kafkaTemplate.send(TOPIC, userId, payload);   // key = userId -> giữ thứ tự theo user
    }
}
