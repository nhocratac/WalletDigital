package com.vng.wallet.domain;

/**
 * Cùng Idempotency-Key nhưng khác payload trên đường <em>transfer</em> (chân OUT).
 *
 * <p>Tách khỏi {@link IdempotencyKeyConflictException} (vốn map 422 cho topup/withdraw — Stage 2)
 * để web layer map transfer-conflict sang <b>409</b> theo plan SP6 Task 4 / design error-contract
 * ("Cùng key, khác payload -&gt; 409"). Tiền KHÔNG bao giờ chuyển lần hai — replay bị từ chối.</p>
 */
public class TransferIdempotencyConflictException extends IdempotencyKeyConflictException {
    public TransferIdempotencyConflictException(String key) {
        super(key);
    }
}
