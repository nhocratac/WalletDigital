package com.vng.kyc.infrastructure.outbox;

import com.vng.kyc.domain.KycEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * ADAPTER outbox (O1) — thay KafkaKycEventPublisher ở đường revoke khi bật cờ Kafka.
 * publishKycRevoked() KHÔNG gọi Kafka trực tiếp: chỉ ghi 1 row PENDING vào bảng outbox,
 * trong CÙNG transaction với thay đổi nghiệp vụ (KycService.revoke() là @Transactional) —
 * nếu tx rollback thì row outbox cũng biến mất cùng lúc (nguyên tử). Một relay riêng
 * (OutboxRelay, Task 3) sẽ đọc PENDING và thật sự publish lên Kafka.
 */
@Component
@ConditionalOnProperty(name = "kyc.events.kafka-enabled", havingValue = "true")
public class OutboxKycEventPublisher implements KycEventPublisher {

    public static final String TOPIC = "kyc.revoked";

    private final OutboxRepository outboxRepository;

    public OutboxKycEventPublisher(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Override
    public void publishKycRevoked(String userId, String reason) {
        String payload = "{\"userId\":\"" + userId + "\",\"reason\":\"" + reason.replace("\"", "'")
                + "\",\"revokedAt\":\"" + Instant.now() + "\"}";
        outboxRepository.save(new OutboxEventEntity(
                userId, TOPIC, payload, OutboxStatus.PENDING, Instant.now(), null));
    }
}
