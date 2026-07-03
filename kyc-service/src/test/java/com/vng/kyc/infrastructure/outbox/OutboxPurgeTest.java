package com.vng.kyc.infrastructure.outbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OutboxPurge (Task 4, O5): xoá các row SENT đã cũ hơn TTL để bảng outbox không phình vô hạn.
 * Bật cùng cờ {@code kyc.events.kafka-enabled=true} như OutboxRelay — set TTL nhỏ (0 ngày) để so
 * sánh trực tiếp với các mốc "cũ"/"gần đây" ở đơn vị giờ trong test, và gọi thẳng
 * {@code purgeOldSentEvents()} (không qua scheduler) để giữ determinism.
 */
@SpringBootTest(properties = {
        "kyc.events.kafka-enabled=true",
        "kyc.outbox.relay-initial-delay-ms=2147483647",
        "kyc.outbox.relay-interval-ms=2147483647",
        "kyc.outbox.purge-initial-delay-ms=2147483647",
        "kyc.outbox.purge-interval-ms=2147483647",
        "kyc.outbox.ttl-days=7"
})
class OutboxPurgeTest {

    @Autowired OutboxPurge outboxPurge;
    @Autowired OutboxRepository outboxRepository;
    @Autowired SpringDataOutboxJpa outboxJpa;

    private OutboxEventEntity save(OutboxStatus status, Instant sentAt) {
        OutboxEventEntity saved = outboxRepository.save(new OutboxEventEntity(
                "purge-agg", "kyc.revoked", "{}", status, Instant.now(), sentAt));
        if (status == OutboxStatus.SENT) {
            outboxJpa.findById(saved.getId()).ifPresent(e -> {
                e.setStatus(OutboxStatus.SENT);
                e.setSentAt(sentAt);
                outboxJpa.save(e);
            });
        }
        return saved;
    }

    @Test
    void sentRowOlderThanTtl_isDeletedByPurge() {
        OutboxEventEntity oldSent = save(OutboxStatus.SENT, Instant.now().minus(8, java.time.temporal.ChronoUnit.DAYS));

        outboxPurge.purgeOldSentEvents();

        assertTrue(outboxJpa.findById(oldSent.getId()).isEmpty(),
                "SENT row cũ hơn TTL phải bị xoá sau purge");
    }

    @Test
    void recentSentRow_isNotDeletedByPurge() {
        OutboxEventEntity recentSent = save(OutboxStatus.SENT, Instant.now().minusSeconds(3600));

        outboxPurge.purgeOldSentEvents();

        assertTrue(outboxJpa.findById(recentSent.getId()).isPresent(),
                "SENT row gần đây (chưa quá TTL) phải còn tồn tại sau purge");
    }

    @Test
    void oldPendingRow_isNotDeletedByPurge_regardlessOfAge() {
        OutboxEventEntity oldPending = outboxRepository.save(new OutboxEventEntity(
                "purge-agg-pending", "kyc.revoked", "{}", OutboxStatus.PENDING,
                Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS), null));

        outboxPurge.purgeOldSentEvents();

        assertTrue(outboxJpa.findById(oldPending.getId()).isPresent(),
                "PENDING row (dù cũ) không bao giờ bị purge, chỉ SENT mới bị xoá");
    }
}
