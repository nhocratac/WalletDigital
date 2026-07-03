package com.vng.kyc.infrastructure.events;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Publisher Kafka thực sự cho kyc.revoked. KHÔNG còn implements KycEventPublisher / không
 * còn nằm trên đường revoke() (Task 2 thay bằng OutboxKycEventPublisher ghi outbox, atomic
 * với tx nghiệp vụ). Giữ lại để OutboxRelay (Task 3) tái sử dụng cho việc publish thật.
 */
@Component
@ConditionalOnProperty(name = "kyc.events.kafka-enabled", havingValue = "true")
public class KafkaKycEventPublisher {

    public static final String TOPIC = "kyc.revoked";
    /** Kafka record header carrying the correlation ID (OB5). Matches the consumer side. */
    public static final String TRACE_ID_HEADER = "traceId";
    private static final String MDC_TRACE_KEY = "traceId";

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaKycEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishKycRevoked(String userId, String reason) {
        String payload = "{\"userId\":\"" + userId + "\",\"reason\":\"" + reason.replace("\"", "'")
                + "\",\"revokedAt\":\"" + Instant.now() + "\"}";
        // key = userId -> giữ thứ tự theo user; traceId vào HEADER (metadata, không vào payload — OB5).
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, userId, payload);
        String traceId = MDC.get(MDC_TRACE_KEY);
        if (traceId != null && !traceId.isBlank()) {
            record.headers().add(TRACE_ID_HEADER, traceId.getBytes(StandardCharsets.UTF_8));
        }
        kafkaTemplate.send(record);
    }
}
