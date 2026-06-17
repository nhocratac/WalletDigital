package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.idempotency.IdempotencyRecord;
import com.vng.wallet.idempotency.IdempotencyStore;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * ADAPTER — cài PORT {@link IdempotencyStore} bằng JPA. Mapping do MapStruct sinh. Routed per-tenant
 * schema như mọi repo khác (SP5 TenantContext). {@code save} dựa UNIQUE PK để claim key
 * (reserve-key-FIRST): trùng → DataIntegrityViolationException (race loser fail INSERT).
 *
 * <p>⚠️ {@code save} dùng {@link EntityManager#persist} (INSERT thuần), KHÔNG Spring Data
 * {@code save()}: vì id (idempotency_key) là assigned PK, {@code save()} sẽ MERGE (SELECT-rồi-UPDATE)
 * → trùng key bị "nuốt" thành update, KHÔNG ném DIVE → vỡ reserve-first. {@code persist} ép INSERT,
 * trùng PK → DataIntegrityViolationException → race loser fail đúng như thiết kế.
 */
@Repository
public class JpaIdempotencyStore implements IdempotencyStore {

    @PersistenceContext
    private EntityManager em;

    private final SpringDataIdempotencyJpa jpa;
    private final IdempotencyRecordMapper mapper;

    public JpaIdempotencyStore(SpringDataIdempotencyJpa jpa, IdempotencyRecordMapper mapper) {
        this.jpa = jpa;
        this.mapper = mapper;
    }

    @Override
    public Optional<IdempotencyRecord> find(String idempotencyKey) {
        return jpa.findById(idempotencyKey).map(mapper::toDomain);
    }

    @Override
    @Transactional
    public IdempotencyRecord save(IdempotencyRecord record) {
        em.persist(mapper.toEntity(record));   // INSERT thuần — claim key, trùng PK → DIVE
        return record;
    }

    @Override
    @Transactional
    public void updateResultRef(String idempotencyKey, String resultRef) {
        IdempotencyRecordEntity existing = jpa.findById(idempotencyKey).orElseThrow(
                () -> new IllegalStateException("idempotency_record not found for key=" + idempotencyKey));
        jpa.save(new IdempotencyRecordEntity(
                existing.getIdempotencyKey(),
                existing.getOperationType(),
                existing.getRequestFingerprint(),
                resultRef,
                existing.getCreatedAt()));
    }
}
