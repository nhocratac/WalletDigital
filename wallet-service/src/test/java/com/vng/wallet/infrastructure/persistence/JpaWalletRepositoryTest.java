package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WalletTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import({JpaWalletRepository.class, WalletMapperImpl.class})   // nạp adapter + mapper MapStruct sinh ra vào test context
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
        repository.saveTransaction(new WalletTransaction(null, w.getId(), WalletTransaction.Type.WITHDRAW,
                new BigDecimal("30"), "k-w", new BigDecimal("70"), Instant.now()));
        em.flush(); em.clear();

        java.util.List<WalletTransaction> sus = repository.findWithdrawalsForUserSince("user-A", cutoff);
        assertEquals(1, sus.size());
        assertEquals(WalletTransaction.Type.WITHDRAW, sus.get(0).type());
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

        Wallet stale = new Wallet(saved.getId(), "user-1", "Alice", new BigDecimal("999.00"), 0L); // version cũ
        assertThrows(org.springframework.dao.OptimisticLockingFailureException.class, () -> {
            repository.save(stale);
            walletJpa.flush(); // flush qua proxy Spring Data để có exception translation
        });
    }

    @Test
    void duplicateIdempotencyKey_violatesDbConstraint() {
        Wallet w = repository.save(Wallet.createNew("user-1", "Bob"));
        repository.saveTransaction(new WalletTransaction(null, w.getId(),
                WalletTransaction.Type.TOPUP, BigDecimal.ONE, "dup-key", BigDecimal.ONE, Instant.now()));
        em.flush(); // ghi bút toán đầu xuống DB trước

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> {
            repository.saveTransaction(new WalletTransaction(null, w.getId(),
                    WalletTransaction.Type.TOPUP, BigDecimal.ONE, "dup-key", BigDecimal.ONE, Instant.now()));
            txJpa.flush(); // flush qua proxy Spring Data để có exception translation
        });
    }
}
