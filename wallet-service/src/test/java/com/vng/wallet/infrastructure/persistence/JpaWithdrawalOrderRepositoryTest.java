package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.domain.WithdrawalOrder;
import com.vng.wallet.domain.WithdrawalOrderRepository;
import com.vng.wallet.domain.WithdrawalState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import({JpaWithdrawalOrderRepository.class, WithdrawalOrderMapperImpl.class})
// SP5 Task 3: scope to tenant persistence package; keep the master repo out of this slice (see
// JpaWalletRepositoryTest for rationale).
@EnableJpaRepositories(basePackages = "com.vng.wallet.infrastructure.persistence")
class JpaWithdrawalOrderRepositoryTest {

    @Autowired
    private WithdrawalOrderRepository repository;

    @Autowired
    private TestEntityManager em;

    @Autowired
    private SpringDataWithdrawalOrderJpa jpa;

    @Test
    void saveThenFindByIdempotencyKey_roundTrips() {
        WithdrawalOrder o = repository.save(
                WithdrawalOrder.create("user-A", 7L, new BigDecimal("30.00"), "idem-1", "wd-ref-1"));
        assertNotNull(o.getId());
        em.flush(); em.clear();

        Optional<WithdrawalOrder> found = repository.findByIdempotencyKey("idem-1");
        assertTrue(found.isPresent());
        assertEquals("user-A", found.get().getUserId());
        assertEquals(7L, found.get().getWalletId());
        assertEquals(0, new BigDecimal("30.00").compareTo(found.get().getAmount()));
        assertEquals(WithdrawalState.PENDING, found.get().getState());
        assertEquals("wd-ref-1", found.get().getBankRef());
        assertEquals("idem-1", found.get().getIdempotencyKey());
    }

    @Test
    void findByBankRef_roundTrips() {
        repository.save(WithdrawalOrder.create("user-A", 7L, new BigDecimal("30"), "idem-1", "wd-ref-1"));
        em.flush(); em.clear();

        assertTrue(repository.findByBankRef("wd-ref-1").isPresent());
        assertTrue(repository.findByBankRef("nope").isEmpty());
    }

    @Test
    void duplicateIdempotencyKey_violatesDbConstraint() {
        repository.save(WithdrawalOrder.create("user-A", 7L, new BigDecimal("30"), "dup-idem", "ref-x"));
        em.flush();

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> {
            repository.save(WithdrawalOrder.create("user-A", 7L, new BigDecimal("30"), "dup-idem", "ref-y"));
            jpa.flush();
        });
    }

    @Test
    void duplicateBankRef_violatesDbConstraint() {
        repository.save(WithdrawalOrder.create("user-A", 7L, new BigDecimal("30"), "idem-a", "dup-ref"));
        em.flush();

        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> {
            repository.save(WithdrawalOrder.create("user-A", 7L, new BigDecimal("30"), "idem-b", "dup-ref"));
            jpa.flush();
        });
    }

    @Test
    void findReconcilable_returnsOnlyPendingAndSent() {
        repository.save(WithdrawalOrder.create("user-A", 7L, new BigDecimal("10"), "i-pending", "r-pending"));

        WithdrawalOrder sent = WithdrawalOrder.create("user-A", 7L, new BigDecimal("10"), "i-sent", "r-sent");
        sent.markSent();
        repository.save(sent);

        WithdrawalOrder settled = WithdrawalOrder.create("user-A", 7L, new BigDecimal("10"), "i-settled", "r-settled");
        settled.markSent();
        settled.markSettled();
        repository.save(settled);

        WithdrawalOrder failed = WithdrawalOrder.create("user-A", 7L, new BigDecimal("10"), "i-failed", "r-failed");
        failed.markSent();
        failed.markFailed("rejected");
        repository.save(failed);

        WithdrawalOrder review = WithdrawalOrder.create("user-A", 7L, new BigDecimal("10"), "i-review", "r-review");
        review.markSent();
        for (int i = 0; i < WithdrawalOrder.MAX_ATTEMPTS; i++) review.recordUnknownAttempt();
        review.escalateIfExhausted();
        assertEquals(WithdrawalState.NEEDS_MANUAL_REVIEW, review.getState());
        repository.save(review);

        em.flush(); em.clear();

        List<WithdrawalOrder> reconcilable = repository.findReconcilable(100);
        List<String> refs = reconcilable.stream().map(WithdrawalOrder::getBankRef).sorted().toList();
        assertEquals(List.of("r-pending", "r-sent"), refs,
                "findReconcilable chỉ trả PENDING/SENT — không trả SETTLED/FAILED/NEEDS_MANUAL_REVIEW");
    }

    @Test
    void findReconcilable_respectsLimit() {
        for (int i = 0; i < 5; i++) {
            repository.save(WithdrawalOrder.create("user-A", 7L, new BigDecimal("10"), "i-" + i, "r-" + i));
        }
        em.flush(); em.clear();

        assertEquals(2, repository.findReconcilable(2).size());
    }

    @Test
    void findByIdAndUserId_scopesOwnership() {
        WithdrawalOrder o = repository.save(
                WithdrawalOrder.create("user-A", 7L, new BigDecimal("30"), "idem-scoped", "ref-scoped"));
        em.flush(); em.clear();

        assertTrue(repository.findByIdAndUserId(o.getId(), "user-A").isPresent());
        assertTrue(repository.findByIdAndUserId(o.getId(), "user-B").isEmpty(),
                "order người khác -> như không tồn tại (scoped D2)");
    }

    @Test
    void version_startsAtZero_andIncrementsOnUpdate() {
        WithdrawalOrder saved = repository.save(
                WithdrawalOrder.create("user-A", 7L, new BigDecimal("30"), "idem-v", "ref-v"));
        em.flush(); em.clear();

        WithdrawalOrder reloaded = repository.findByIdempotencyKey("idem-v").orElseThrow();
        assertEquals(0L, reloaded.getVersion(), "INSERT đầu tiên -> version 0");

        reloaded.markSent();
        repository.save(reloaded);
        em.flush(); em.clear();

        WithdrawalOrder afterUpdate = repository.findByIdempotencyKey("idem-v").orElseThrow();
        assertEquals(1L, afterUpdate.getVersion(), "UPDATE -> version tăng (mapper/save không làm rơi version)");
        assertEquals(WithdrawalState.SENT, afterUpdate.getState());
    }
}
