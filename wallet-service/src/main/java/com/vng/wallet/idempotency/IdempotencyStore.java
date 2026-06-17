package com.vng.wallet.idempotency;

import java.time.Instant;
import java.util.Optional;

/**
 * PORT — kho idempotency_record (SP7 Bước 1, L2). Định nghĩa bởi tầng nghiệp vụ; KHÔNG nói gì về
 * JPA/SQL. Adapter ở infrastructure cài đặt, routed per-tenant-schema như mọi repo khác (SP5).
 *
 * <p>Reserve-key-FIRST: {@link #save} claim key qua UNIQUE — trùng key ném
 * {@link org.springframework.dao.DataIntegrityViolationException} (race loser fail INSERT, tiền
 * KHÔNG bao giờ chuyển hai lần). {@link #find} đọc record để recovery (fingerprint khớp → replay;
 * lệch → 409). {@link #updateResultRef} ghi kết quả SAU khi money op xong.
 */
public interface IdempotencyStore {

    /** Đọc record theo key (recovery/replay). Empty nếu chưa claim (vd winner đã rollback). */
    Optional<IdempotencyRecord> find(String idempotencyKey);

    /**
     * Claim key (INSERT). Trùng key → {@link org.springframework.dao.DataIntegrityViolationException}.
     * Gọi TRONG transaction nghiệp vụ, TRƯỚC khi chuyển tiền.
     */
    IdempotencyRecord save(IdempotencyRecord record);

    /** Ghi result_ref SAU khi money op xong (txId/orderId/transferId) để replay trả đúng cái cũ. */
    void updateResultRef(String idempotencyKey, String resultRef);

    /**
     * TTL purge (SP7 Task 6): xoá mọi record có {@code created_at} CŨ HƠN {@code cutoff} TRONG schema
     * tenant hiện tại (routed như mọi op khác). Trả về số record đã xoá. Bảng record KHÔNG phình vì
     * key replay-able đã quá hạn TTL (client sẽ không retry sau hàng ngày).
     */
    int deleteOlderThan(Instant cutoff);
}
