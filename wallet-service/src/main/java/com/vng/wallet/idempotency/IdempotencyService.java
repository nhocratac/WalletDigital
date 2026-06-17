package com.vng.wallet.idempotency;

import com.vng.wallet.domain.IdempotencyKeyConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * SP7 Bước 1 Task 2 (L2): lá chắn dedup tách RA KHỎI sổ cái — reserve-key-FIRST + fingerprint +
 * race recovery (mô hình Stripe/Brandur). Gọi TRONG transaction nghiệp vụ:
 *
 * <pre>
 *   var outcome = idempotencyService.reserveOrReplay(key, opType, fingerprintOf(...));
 *   if (outcome.isReplay()) return loadByResultRef(outcome.resultRef());  // trả đúng kết quả cũ
 *   ... move money ...                                                    // chỉ chạy khi FRESH
 *   idempotencyService.complete(key, resultRef);                          // ghi result_ref
 * </pre>
 *
 * <p><b>Reserve-key-FIRST:</b> INSERT record(key, fingerprint) TRƯỚC khi chuyển tiền — UNIQUE claim
 * key. Trùng key → {@link DataIntegrityViolationException} (race loser fail INSERT) → recovery: đọc
 * record (fingerprint khớp → replay; lệch → 409; record chưa có vì winner rollback → rethrow → 409).
 * Tiền KHÔNG bao giờ chuyển hai lần.
 */
@Service
public class IdempotencyService {

    private final IdempotencyStore store;

    public IdempotencyService(IdempotencyStore store) {
        this.store = store;
    }

    /**
     * Kết quả reserve: FRESH (key mới, đã claim — chạy money op rồi {@link #complete}) hoặc
     * REPLAY (key đã có với fingerprint khớp — trả lại kết quả cũ qua {@code resultRef}).
     */
    public record ReserveOutcome(boolean fresh, String resultRef) {
        static ReserveOutcome ofFresh() {
            return new ReserveOutcome(true, null);
        }

        static ReserveOutcome ofReplay(String resultRef) {
            return new ReserveOutcome(false, resultRef);
        }

        public boolean isFresh() {
            return fresh;
        }

        public boolean isReplay() {
            return !fresh;
        }
    }

    /**
     * Reserve-key-FIRST. Claim key qua UNIQUE; nếu trùng → recovery. Gọi TRƯỚC khi chuyển tiền.
     *
     * @return FRESH nếu key mới (cho phép chạy op); REPLAY(resultRef) nếu đã xử lý (KHÔNG chạy op)
     * @throws IdempotencyKeyConflictException same-key-different-payload (→ 409)
     */
    public ReserveOutcome reserveOrReplay(String idempotencyKey, String operationType, String fingerprint) {
        try {
            store.save(new IdempotencyRecord(idempotencyKey, operationType, fingerprint, null, Instant.now()));
            return ReserveOutcome.ofFresh();
        } catch (DataIntegrityViolationException dive) {
            // Race loser fail INSERT — đọc record winner đã claim để recovery.
            IdempotencyRecord rec = store.find(idempotencyKey).orElseThrow(() -> dive); // winner rollback → rethrow → 409
            if (!rec.requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyKeyConflictException(idempotencyKey);
            }
            return ReserveOutcome.ofReplay(rec.resultRef());
        }
    }

    /** Ghi result_ref SAU khi money op xong (txId/orderId/transferId) để replay trả đúng cái cũ. */
    public void complete(String idempotencyKey, String resultRef) {
        store.updateResultRef(idempotencyKey, resultRef);
    }

    /**
     * Fingerprint ổn định của payload để phát hiện same-key-different-payload. SHA-256 của các thành
     * phần nối bằng '|' (operationType, walletId|fromId+toId, amount) — thay
     * {@code requireMatchingTransaction/Order}.
     */
    public String fingerprintOf(String operationType, String... payloadParts) {
        StringBuilder sb = new StringBuilder(operationType);
        for (String part : payloadParts) {
            sb.append('|').append(part);
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e); // không xảy ra trên JVM chuẩn
        }
    }
}
