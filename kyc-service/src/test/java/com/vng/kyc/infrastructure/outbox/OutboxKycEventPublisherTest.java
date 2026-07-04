package com.vng.kyc.infrastructure.outbox;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Unit test — publishKycRevoked chỉ ghi 1 row PENDING vào outbox, KHÔNG gọi Kafka. */
class OutboxKycEventPublisherTest {

    static class RecordingOutboxRepository implements OutboxRepository {
        List<OutboxEventEntity> saved = new ArrayList<>();

        @Override
        public OutboxEventEntity save(OutboxEventEntity event) {
            saved.add(event);
            return event;
        }

        @Override
        public List<OutboxEventEntity> findPending(int limit) { throw new UnsupportedOperationException(); }

        @Override
        public void markSent(Long id) { throw new UnsupportedOperationException(); }

        @Override
        public void deleteSentOlderThan(java.time.Instant cutoff) { throw new UnsupportedOperationException(); }
    }

    private final RecordingOutboxRepository repository = new RecordingOutboxRepository();
    private final OutboxKycEventPublisher publisher = new OutboxKycEventPublisher(repository);

    @Test
    void publishKycRevoked_savesOnePendingOutboxRow_withTopicAggregateAndPayload() {
        publisher.publishKycRevoked("user-1", "fraud detected");

        assertEquals(1, repository.saved.size());
        OutboxEventEntity row = repository.saved.get(0);
        assertEquals("user-1", row.getAggregate());
        assertEquals("kyc.revoked", row.getTopic());
        assertEquals(OutboxStatus.PENDING, row.getStatus());
        assertNotNull(row.getCreatedAt());
        assertTrue(row.getPayload().contains("\"userId\":\"user-1\""), "payload phải chứa userId");
        assertTrue(row.getPayload().contains("\"reason\":\"fraud detected\""), "payload phải chứa reason");
    }
}
