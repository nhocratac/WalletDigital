package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.idempotency.IdempotencyRecord;
import com.vng.wallet.idempotency.IdempotencyStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SP7 Bước 1 Task 1: kho idempotency_record (expand). H2 slice — round-trip save/find, và UNIQUE(key)
 * chặn trùng (reserve-key-FIRST: race loser fail INSERT). (Cô lập multi-tenant chứng minh trên MySQL
 * thật trong {@link com.vng.wallet.tenancy.IdempotencyStoreTenantIsolationIntegrationTest}.)
 */
@DataJpaTest
@Import({JpaIdempotencyStore.class, IdempotencyRecordMapperImpl.class})
@EnableJpaRepositories(basePackages = "com.vng.wallet.infrastructure.persistence")
class JpaIdempotencyStoreTest {

    @Autowired
    private IdempotencyStore store;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private SpringDataIdempotencyJpa jpa;

    @Test
    void saveThenFind_roundTrips() {
        Instant now = Instant.parse("2026-06-17T10:00:00Z");
        store.save(new IdempotencyRecord("key-1", "TOPUP", "fp-abc", null, now));
        em.flush(); em.clear();

        Optional<IdempotencyRecord> found = store.find("key-1");
        assertTrue(found.isPresent());
        assertEquals("key-1", found.get().idempotencyKey());
        assertEquals("TOPUP", found.get().operationType());
        assertEquals("fp-abc", found.get().requestFingerprint());
        assertNull(found.get().resultRef(), "result_ref null tới khi op xong");
        assertEquals(now, found.get().createdAt());
    }

    @Test
    void find_emptyWhenMissing() {
        assertTrue(store.find("nope").isEmpty());
    }

    @Test
    void duplicateIdempotencyKey_violatesUniqueConstraint() {
        // First claim, then DETACH (em.clear) to simulate a SEPARATE transaction — the second claim
        // must hit the DB UNIQUE (not the L1 cache) and fail, mirroring the race loser's INSERT.
        store.save(new IdempotencyRecord("dup-key", "TOPUP", "fp-1", null, Instant.now()));
        em.flush();
        em.clear();

        assertThrows(DataIntegrityViolationException.class, () -> {
            store.save(new IdempotencyRecord("dup-key", "WITHDRAW", "fp-2", null, Instant.now()));
            jpa.flush(); // flush qua proxy Spring Data để có exception translation (DB UNIQUE → DIVE)
        });
    }

    @Test
    void updateResultRef_setsResultAfterOpCompletes() {
        store.save(new IdempotencyRecord("key-c", "TRANSFER", "fp-c", null, Instant.now()));
        em.flush(); em.clear();

        store.updateResultRef("key-c", "transfer-99");
        em.flush(); em.clear();

        IdempotencyRecord reloaded = store.find("key-c").orElseThrow();
        assertEquals("transfer-99", reloaded.resultRef(), "result_ref cập nhật SAU khi op xong");
        assertEquals("fp-c", reloaded.requestFingerprint(), "các field khác giữ nguyên");
    }
}
