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
     * Reserve-key-FIRST claim. Gọi TRONG transaction nghiệp vụ, TRƯỚC khi chuyển tiền: INSERT record
     * claim key qua UNIQUE. Trùng key (sequential retry HOẶC race loser) → ném
     * {@link DataIntegrityViolationException} — caller phải để DIVE thoát RA KHỎI transaction nghiệp
     * vụ (rollback), rồi gọi {@link #recover} ở TRANSACTION MỚI (read sau khi tx hỏng đã thoát — nếu
     * recover đọc trong cùng tx đã bị đánh dấu rollback-only thì query fail). Đây là bài học SP2/SP4:
     * winner/loser recovery phải đọc NGOÀI transaction thất bại.
     *
     * @throws DataIntegrityViolationException key đã được claim (→ caller catch ngoài tx → {@link #recover})
     */
    public void reserve(String idempotencyKey, String operationType, String fingerprint) {
        store.save(new IdempotencyRecord(idempotencyKey, operationType, fingerprint, null, Instant.now()));
    }

    /**
     * Recovery SAU khi {@link #reserve} ném DIVE — gọi NGOÀI transaction nghiệp vụ đã rollback (đọc ở
     * transaction mới). fingerprint khớp → REPLAY(resultRef cũ); lệch → 409; record chưa có (winner
     * cũng rollback) → rethrow DIVE gốc → handler map 409. Tiền KHÔNG bao giờ chuyển hai lần.
     *
     * @throws IdempotencyKeyConflictException same-key-different-payload (→ 409)
     */
    public ReserveOutcome recover(String idempotencyKey, String fingerprint, DataIntegrityViolationException dive) {
        IdempotencyRecord rec = store.find(idempotencyKey).orElseThrow(() -> dive); // winner rollback → rethrow → 409
        if (!rec.requestFingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyConflictException(idempotencyKey);
        }
        return ReserveOutcome.ofReplay(rec.resultRef());
    }

    /**
     * Tiện ích claim-or-recover trong MỘT lời gọi (dùng khi recovery read có thể chạy ngay — vd unit
     * test fake store, hoặc context không có rollback-only). Production path tách {@link #reserve}
     * (trong tx) + {@link #recover} (ngoài tx) để recovery đọc NGOÀI transaction thất bại.
     */
    public ReserveOutcome reserveOrReplay(String idempotencyKey, String operationType, String fingerprint) {
        try {
            reserve(idempotencyKey, operationType, fingerprint);
            return ReserveOutcome.ofFresh();
        } catch (DataIntegrityViolationException dive) {
            return recover(idempotencyKey, fingerprint, dive);
        }
    }

    /**
     * Đọc record theo key — dùng cho replay pre-check NGOÀI transaction (vd withdraw phải replay
     * TRƯỚC cổng KYC, D4). Empty nếu key chưa được claim.
     */
    public java.util.Optional<IdempotencyRecord> find(String idempotencyKey) {
        return store.find(idempotencyKey);
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
