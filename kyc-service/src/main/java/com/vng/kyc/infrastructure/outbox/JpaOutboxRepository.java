package com.vng.kyc.infrastructure.outbox;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

/** ADAPTER: cài port outbox bằng JPA. */
@Repository
public class JpaOutboxRepository implements OutboxRepository {

    private final SpringDataOutboxJpa outboxJpa;

    public JpaOutboxRepository(SpringDataOutboxJpa outboxJpa) {
        this.outboxJpa = outboxJpa;
    }

    @Override
    public OutboxEventEntity save(OutboxEventEntity event) {
        return outboxJpa.save(event);
    }

    @Override
    public List<OutboxEventEntity> findPending(int limit) {
        return outboxJpa.findByStatusOrderByIdAsc(OutboxStatus.PENDING, PageRequest.of(0, limit));
    }

    @Override
    public void markSent(Long id) {
        outboxJpa.findById(id).ifPresent(e -> {
            e.setStatus(OutboxStatus.SENT);
            e.setSentAt(Instant.now());
            outboxJpa.save(e);
        });
    }

    @Override
    public void deleteSentOlderThan(Instant cutoff) {
        outboxJpa.deleteByStatusAndSentAtBefore(OutboxStatus.SENT, cutoff);
    }
}
