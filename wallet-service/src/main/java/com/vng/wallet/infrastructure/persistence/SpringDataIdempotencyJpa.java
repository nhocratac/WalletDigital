package com.vng.wallet.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data sinh save/findById cho idempotency_record (PK = idempotency_key). */
public interface SpringDataIdempotencyJpa extends JpaRepository<IdempotencyRecordEntity, String> {
}
