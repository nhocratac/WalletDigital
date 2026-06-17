package com.vng.wallet.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/** Spring Data sinh save/findById cho idempotency_record (PK = idempotency_key). */
public interface SpringDataIdempotencyJpa extends JpaRepository<IdempotencyRecordEntity, String> {

    /** TTL purge (SP7 Task 6): bulk DELETE record cũ hơn cutoff. Trả về số dòng đã xoá. */
    @Modifying
    @Query("DELETE FROM IdempotencyRecordEntity r WHERE r.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") Instant cutoff);
}
