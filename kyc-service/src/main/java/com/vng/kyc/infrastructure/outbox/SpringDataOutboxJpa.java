package com.vng.kyc.infrastructure.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface SpringDataOutboxJpa extends JpaRepository<OutboxEventEntity, Long> {
    List<OutboxEventEntity> findByStatusOrderByIdAsc(OutboxStatus status, Pageable pageable);
    void deleteByStatusAndSentAtBefore(OutboxStatus status, Instant cutoff);
}
