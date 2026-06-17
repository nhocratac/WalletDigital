package com.vng.wallet.idempotency;

import com.vng.wallet.domain.IdempotencyKeyConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SP7 Bước 1 Task 2: {@link IdempotencyService} — reserve-key-FIRST + fingerprint + race recovery.
 *
 * <p>Ma trận (fake store, không cần DB):
 * <ul>
 *   <li>key mới → FRESH (record IN, resultRef null) → cho phép chạy op.</li>
 *   <li>key đã có, fingerprint khớp → REPLAY(resultRef cũ), KHÔNG chạy op.</li>
 *   <li>key đã có, fingerprint lệch → {@link IdempotencyKeyConflictException} (→ 409).</li>
 *   <li>race: save() ném DIVE → recovery đọc record (khớp replay / lệch 409 / chưa có → rethrow → 409).</li>
 *   <li>fingerprintOf ổn định &amp; phân biệt payload khác nhau.</li>
 * </ul>
 */
class IdempotencyServiceTest {

    /** Fake store in-memory; save() là INSERT thuần (trùng key → DIVE) mô phỏng UNIQUE PK. */
    static final class FakeStore implements IdempotencyStore {
        final Map<String, IdempotencyRecord> rows = new HashMap<>();
        /** Khi true: save() ném DIVE mà KHÔNG ghi (mô phỏng race loser fail INSERT). */
        boolean failNextSaveWithDive = false;

        @Override
        public Optional<IdempotencyRecord> find(String idempotencyKey) {
            return Optional.ofNullable(rows.get(idempotencyKey));
        }

        @Override
        public IdempotencyRecord save(IdempotencyRecord record) {
            if (failNextSaveWithDive || rows.containsKey(record.idempotencyKey())) {
                throw new DataIntegrityViolationException("duplicate key " + record.idempotencyKey());
            }
            rows.put(record.idempotencyKey(), record);
            return record;
        }

        @Override
        public void updateResultRef(String idempotencyKey, String resultRef) {
            IdempotencyRecord r = rows.get(idempotencyKey);
            rows.put(idempotencyKey, new IdempotencyRecord(
                    r.idempotencyKey(), r.operationType(), r.requestFingerprint(), resultRef, r.createdAt()));
        }

        @Override
        public int deleteOlderThan(java.time.Instant cutoff) {
            int before = rows.size();
            rows.values().removeIf(r -> r.createdAt().isBefore(cutoff));
            return before - rows.size();
        }
    }

    private final FakeStore store = new FakeStore();
    private final IdempotencyService service = new IdempotencyService(store);

    @Test
    void freshKey_reservesAndAllowsOp() {
        var outcome = service.reserveOrReplay("key-new", "TOPUP", "fp-1");

        assertTrue(outcome.isFresh(), "key mới → FRESH, cho phép chạy op");
        assertFalse(outcome.isReplay());
        assertTrue(store.find("key-new").isPresent(), "record được claim (IN)");
        assertNull(store.find("key-new").get().resultRef(), "resultRef null tới khi op xong");
    }

    @Test
    void existingKey_matchingFingerprint_signalsReplay() {
        store.save(new IdempotencyRecord("key-dup", "TOPUP", "fp-1", "tx-42", Instant.now()));

        var outcome = service.reserveOrReplay("key-dup", "TOPUP", "fp-1");

        assertTrue(outcome.isReplay(), "fingerprint khớp → REPLAY, KHÔNG chạy op");
        assertFalse(outcome.isFresh());
        assertEquals("tx-42", outcome.resultRef(), "replay trả result_ref cũ");
    }

    @Test
    void existingKey_differentFingerprint_throwsConflict() {
        store.save(new IdempotencyRecord("key-dup", "TOPUP", "fp-1", "tx-42", Instant.now()));

        assertThrows(IdempotencyKeyConflictException.class,
                () -> service.reserveOrReplay("key-dup", "TOPUP", "fp-DIFFERENT"));
    }

    @Test
    void race_diveOnSave_recoversToReplayWhenFingerprintMatches() {
        // Winner đã commit record với resultRef; loser save() fail DIVE → recovery đọc record → khớp → replay.
        store.rows.put("key-race",
                new IdempotencyRecord("key-race", "TRANSFER", "fp-1", "transfer-7", Instant.now()));
        store.failNextSaveWithDive = true;

        var outcome = service.reserveOrReplay("key-race", "TRANSFER", "fp-1");

        assertTrue(outcome.isReplay(), "race loser → recovery → replay kết quả winner");
        assertEquals("transfer-7", outcome.resultRef());
    }

    @Test
    void race_diveOnSave_throwsConflictWhenFingerprintDiffers() {
        store.rows.put("key-race",
                new IdempotencyRecord("key-race", "TRANSFER", "fp-1", "transfer-7", Instant.now()));
        store.failNextSaveWithDive = true;

        assertThrows(IdempotencyKeyConflictException.class,
                () -> service.reserveOrReplay("key-race", "TRANSFER", "fp-OTHER"));
    }

    @Test
    void race_diveOnSave_winnerRolledBack_rethrowsAsConflict() {
        // DIVE nhưng record KHÔNG có (winner đã rollback) → không recover được → rethrow → 409.
        store.failNextSaveWithDive = true; // find sẽ empty vì rows trống

        assertThrows(RuntimeException.class,
                () -> service.reserveOrReplay("key-gone", "TOPUP", "fp-1"));
    }

    @Test
    void complete_writesResultRef() {
        service.reserveOrReplay("key-c", "WITHDRAW", "fp-1");

        service.complete("key-c", "order-99");

        assertEquals("order-99", store.find("key-c").orElseThrow().resultRef());
    }

    @Test
    void fingerprintOf_isStableForSamePayload() {
        String a = service.fingerprintOf("TOPUP", "10", "100.00");
        String b = service.fingerprintOf("TOPUP", "10", "100.00");
        assertEquals(a, b, "cùng payload → cùng fingerprint (ổn định)");
    }

    @Test
    void fingerprintOf_distinguishesDifferentPayloads() {
        String base = service.fingerprintOf("TOPUP", "10", "100.00");
        assertNotEquals(base, service.fingerprintOf("WITHDRAW", "10", "100.00"), "opType khác");
        assertNotEquals(base, service.fingerprintOf("TOPUP", "11", "100.00"), "wallet khác");
        assertNotEquals(base, service.fingerprintOf("TOPUP", "10", "200.00"), "amount khác");
    }
}
