package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WalletTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import({JpaWalletRepository.class, WalletMapperImpl.class})   // nạp adapter + mapper MapStruct sinh ra vào test context
// SP5 Task 3: scope this slice to the tenant persistence package only. Without this, the slice's
// repo auto-scan picks up the master TenantRegistryRepository, whose custom impl needs the `master`
// persistence unit (not present in a @DataJpaTest single-EMF slice).
@EnableJpaRepositories(basePackages = "com.vng.wallet.infrastructure.persistence")
class JpaWalletRepositoryTest {

    @Autowired
    private WalletRepository repository;   // tiêm qua PORT, không phải class cụ thể

    @Autowired
    private TestEntityManager em;

    @Autowired
    private SpringDataWalletTransactionJpa txJpa;

    @Autowired
    private SpringDataWalletJpa walletJpa;

    @Test
    void findByIdAndUserId_scopesOwnership() {
        Wallet w = repository.save(Wallet.createNew("user-A", "Alice"));
        em.flush(); em.clear();

        assertTrue(repository.findByIdAndUserId(w.getId(), "user-A").isPresent());
        assertTrue(repository.findByIdAndUserId(w.getId(), "user-B").isEmpty(), "ví người khác -> như không tồn tại");
    }

    @Test
    void findWithdrawalsForUserSince_filtersTypeAndTime() {
        Wallet w = repository.save(Wallet.createNew("user-A", "Alice"));
        Instant cutoff = Instant.now().minusSeconds(60);
        repository.saveTransaction(new WalletTransaction(null, w.getId(), WalletTransaction.Type.TOPUP,
                new BigDecimal("100"), "k-t", new BigDecimal("100"), Instant.now()));
        repository.saveTransaction(new WalletTransaction(null, w.getId(), WalletTransaction.Type.WITHDRAW_HOLD,
                new BigDecimal("30"), "k-w", new BigDecimal("70"), Instant.now()));
        em.flush(); em.clear();

        java.util.List<WalletTransaction> sus = repository.findWithdrawalsForUserSince("user-A", cutoff);
        assertEquals(1, sus.size());
        assertEquals(WalletTransaction.Type.WITHDRAW_HOLD, sus.get(0).type());
        assertTrue(repository.findWithdrawalsForUserSince("user-A", Instant.now().plusSeconds(60)).isEmpty());
    }

    @Test
    void saveThenFind_roundTripsThroughDatabase() {
        Wallet saved = repository.save(Wallet.createNew("user-1", "Alice"));

        assertNotNull(saved.getId());

        em.flush();   // push INSERT to DB
        em.clear();   // evict L1 cache so findById reloads from the row

        Optional<Wallet> found = repository.findByIdAndUserId(saved.getId(), "user-1");
        assertTrue(found.isPresent());
        assertEquals("Alice", found.get().getOwnerName());
        assertEquals(0, BigDecimal.ZERO.compareTo(found.get().getBalance()));
    }

    @Test
    void findById_emptyWhenMissing() {
        assertTrue(repository.findByIdAndUserId(999L, "user-1").isEmpty());
    }

    @Test
    void findById_returnsWalletRegardlessOfOwner_forReceiverLookup() {
        // SP6 TR5: receiver wallet loaded by id only (NOT scoped to caller). A caller transferring
        // to someone else's wallet must still find it. Within a tenant schema (SP5 routing), only
        // wallets of the current tenant exist — cross-tenant receivers simply won't be present.
        Wallet receiver = repository.save(Wallet.createNew("user-B", "Bob"));
        em.flush(); em.clear();

        // caller is user-A, receiver belongs to user-B: scoped lookup misses, by-id lookup hits
        assertTrue(repository.findByIdAndUserId(receiver.getId(), "user-A").isEmpty(),
                "scoped query must NOT return another owner's wallet (sender D2 path)");
        Optional<Wallet> byId = repository.findById(receiver.getId());
        assertTrue(byId.isPresent(), "findById must return the receiver wallet regardless of owner (TR5)");
        assertEquals("user-B", byId.get().getUserId());
        assertEquals("Bob", byId.get().getOwnerName());
    }

    @Test
    void findById_emptyWhenWalletAbsentFromTenantSchema() {
        // No wallet with this id exists in the current tenant schema -> empty (cross-tenant receiver
        // is enforced "for free" by SP5 routing: it lives in another schema, invisible here).
        assertTrue(repository.findById(424242L).isEmpty());
    }

    @Test
    void transactionRoundTrip_andIdempotencyLookup() {
        Wallet w = repository.save(Wallet.createNew("user-1", "Alice"));
        WalletTransaction tx = repository.saveTransaction(new WalletTransaction(
                null, w.getId(), WalletTransaction.Type.TOPUP,
                new BigDecimal("50.00"), "key-abc", new BigDecimal("50.00"), Instant.now()));
        em.flush(); em.clear();

        assertNotNull(tx.id());
        var found = repository.findTransactionByIdempotencyKey("key-abc");
        assertTrue(found.isPresent());
        assertEquals(tx.id(), found.get().id());
        assertEquals(0, new BigDecimal("50.00").compareTo(found.get().balanceAfter()));
        assertEquals(1, repository.listTransactions(w.getId()).size());
    }

    @Test
    void listTransactions_returnsRowsOrderedByCreatedAtAscending() {
        Wallet w = repository.save(Wallet.createNew("user-1", "Alice"));
        Instant base = Instant.parse("2026-01-01T00:00:00Z");
        // Chèn CỐ TÌNH lệch thứ tự thời gian để chốt hợp đồng ORDER BY createdAt ASC
        repository.saveTransaction(new WalletTransaction(null, w.getId(),
                WalletTransaction.Type.TOPUP, BigDecimal.ONE, "k-1", BigDecimal.ONE, base.plusSeconds(2)));
        repository.saveTransaction(new WalletTransaction(null, w.getId(),
                WalletTransaction.Type.TOPUP, BigDecimal.ONE, "k-2", BigDecimal.ONE, base));
        repository.saveTransaction(new WalletTransaction(null, w.getId(),
                WalletTransaction.Type.TOPUP, BigDecimal.ONE, "k-3", BigDecimal.ONE, base.plusSeconds(1)));
        em.flush(); em.clear();

        var txs = repository.listTransactions(w.getId());
        assertEquals(3, txs.size());
        assertEquals(java.util.List.of("k-2", "k-3", "k-1"),
                txs.stream().map(WalletTransaction::idempotencyKey).toList(),
                "listTransactions phải trả về theo createdAt tăng dần, không phụ thuộc thứ tự insert");
    }

    @Test
    void version_startsAtZero_andIncrementsOnUpdate() {
        Wallet saved = repository.save(Wallet.createNew("user-1", "Alice")); // ví mới: version = null
        em.flush(); em.clear();

        Wallet reloaded = repository.findByIdAndUserId(saved.getId(), "user-1").orElseThrow();
        assertEquals(0L, reloaded.getVersion(), "INSERT đầu tiên -> version 0");

        reloaded.topup(BigDecimal.TEN);
        repository.save(reloaded);
        em.flush(); em.clear();

        Wallet afterUpdate = repository.findByIdAndUserId(saved.getId(), "user-1").orElseThrow();
        assertEquals(1L, afterUpdate.getVersion(), "UPDATE -> version tăng lên 1 (mapper/save không được làm rơi version)");
        assertEquals(0, BigDecimal.TEN.compareTo(afterUpdate.getBalance()));
    }

    @Test
    void staleVersionWrite_throwsOptimisticLockingFailure() {
        Wallet saved = repository.save(Wallet.createNew("user-1", "Alice"));
        em.flush(); em.clear();

        Wallet current = repository.findByIdAndUserId(saved.getId(), "user-1").orElseThrow(); // version 0
        current.topup(BigDecimal.TEN);
        repository.save(current);
        em.flush(); em.clear(); // DB giờ ở version 1

        Wallet stale = new Wallet(saved.getId(), "user-1", "Alice", new BigDecimal("999.00"), BigDecimal.ZERO, 0L); // version cũ
        assertThrows(org.springframework.dao.OptimisticLockingFailureException.class, () -> {
            repository.save(stale);
            walletJpa.flush(); // flush qua proxy Spring Data để có exception translation
        });
    }

    @Test
    void transferLedgerPair_persistsTransferIdAndNullKeyOnInLeg() {
        Wallet from = repository.save(Wallet.createNew("user-A", "Alice"));
        Wallet to = repository.save(Wallet.createNew("user-B", "Bob"));
        String transferId = "transfer-xyz";

        WalletTransaction out = repository.saveTransaction(new WalletTransaction(
                null, from.getId(), WalletTransaction.Type.TRANSFER_OUT,
                new BigDecimal("30.00"), "key-out", new BigDecimal("70.00"), Instant.now(), transferId));
        WalletTransaction in = repository.saveTransaction(new WalletTransaction(
                null, to.getId(), WalletTransaction.Type.TRANSFER_IN,
                new BigDecimal("30.00"), null, new BigDecimal("30.00"), Instant.now(), transferId));
        em.flush(); em.clear();

        // OUT leg: key present, transferId stored, type correct
        WalletTransaction reloadedOut = repository.findTransactionByIdempotencyKey("key-out").orElseThrow();
        assertEquals(WalletTransaction.Type.TRANSFER_OUT, reloadedOut.type());
        assertEquals(transferId, reloadedOut.transferId());

        // IN leg: idempotency_key NULL is allowed (no UNIQUE violation), transferId shared
        WalletTransaction reloadedIn = repository.listTransactions(to.getId()).get(0);
        assertEquals(WalletTransaction.Type.TRANSFER_IN, reloadedIn.type());
        assertNull(reloadedIn.idempotencyKey(), "IN leg carries no idempotency key");
        assertEquals(transferId, reloadedIn.transferId(), "both legs share one transferId");
        assertEquals(in.id(), reloadedIn.id());
    }

    @Test
    void duplicateIdempotencyKey_isAcceptedByLedger_afterV6DropUnique() {
        // SP7 Bước 1 Task 5 (CONTRACT): V6 đã bỏ uk_wt_idempotency_key. Sổ cái SẠCH constraint trên
        // idempotency_key -> hai bút toán cùng key KHÔNG còn bị DB chặn (drift CÓ CHỦ ĐÍCH: trước Task 5
        // test này assert DIVE từ uk_wt_idempotency_key). Dedup nay enforce ở idempotency_record qua
        // IdempotencyService/WalletService — KHÔNG phải ở ledger nữa -> ledger partitionable (SP7 Bước 2).
        Wallet w = repository.save(Wallet.createNew("user-1", "Bob"));
        repository.saveTransaction(new WalletTransaction(null, w.getId(),
                WalletTransaction.Type.TOPUP, BigDecimal.ONE, "dup-key", BigDecimal.ONE, Instant.now()));
        em.flush(); // ghi bút toán đầu xuống DB trước

        // Bút toán thứ hai cùng key: với UNIQUE đã bỏ, INSERT thành công (không DIVE).
        repository.saveTransaction(new WalletTransaction(null, w.getId(),
                WalletTransaction.Type.TOPUP, BigDecimal.ONE, "dup-key", BigDecimal.ONE, Instant.now()));
        txJpa.flush(); // flush qua proxy Spring Data — không còn ràng buộc UNIQUE để vi phạm
        em.clear();

        // Hai bút toán cùng "dup-key" cùng tồn tại trong sổ cái -> bằng chứng ledger hết UNIQUE.
        assertEquals(2, txJpa.count(), "ledger giữ cả hai bút toán trùng key sau khi bỏ UNIQUE");
    }
}
