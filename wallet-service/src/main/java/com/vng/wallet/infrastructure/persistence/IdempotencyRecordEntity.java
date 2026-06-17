package com.vng.wallet.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * SP7 Bước 1 (L2): bảng idempotency_record — lá chắn dedup tách RA KHỎI sổ cái. {@code idempotencyKey}
 * là PRIMARY KEY = UNIQUE toàn cục TRONG schema tenant (routed per-tenant như mọi entity khác, SP5).
 * KHÔNG có @Version: record là claim-once + complete-once, không có race UPDATE xen kẽ cần lock.
 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecordEntity {

    @Id
    @Column(name = "idempotency_key", length = 255)
    private String idempotencyKey;

    @Column(name = "operation_type", length = 32, nullable = false)
    private String operationType;

    @Column(name = "request_fingerprint", length = 64, nullable = false)
    private String requestFingerprint;

    @Column(name = "result_ref", length = 64)
    private String resultRef;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IdempotencyRecordEntity() {
    }

    public IdempotencyRecordEntity(String idempotencyKey, String operationType,
                                   String requestFingerprint, String resultRef, Instant createdAt) {
        this.idempotencyKey = idempotencyKey;
        this.operationType = operationType;
        this.requestFingerprint = requestFingerprint;
        this.resultRef = resultRef;
        this.createdAt = createdAt;
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public String getOperationType() { return operationType; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public String getResultRef() { return resultRef; }
    public Instant getCreatedAt() { return createdAt; }
}
