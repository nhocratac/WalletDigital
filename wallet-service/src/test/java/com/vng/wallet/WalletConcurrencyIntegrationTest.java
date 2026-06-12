package com.vng.wallet;

import com.vng.wallet.application.WalletService;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletTransaction;
import com.vng.wallet.infrastructure.persistence.SpringDataWalletTransactionJpa;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.ConcurrencyFailureException;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Optimistic-lock concurrency coverage (@Version trên Wallet).
 * Gọi WalletService TRỰC TIẾP từ N thread (không qua MockMvc) — mỗi thread một
 * Idempotency-Key riêng, nếu không idempotency short-circuit sẽ che mất race.
 * Loser surface dưới dạng exception (service không retry); GlobalExceptionHandler
 * map ConcurrencyFailureException -> 409 ở tầng HTTP.
 */
@SpringBootTest
class WalletConcurrencyIntegrationTest {

    @Autowired WalletService walletService;
    @Autowired SpringDataWalletTransactionJpa txJpa;

    @Test
    void concurrentWithdraws_noLostUpdates_andLedgerMatchesSuccesses() throws Exception {
        Wallet wallet = walletService.createWallet("user-1", "ConcurrencyCarl");
        long walletId = wallet.getId();
        walletService.topup(walletId, "user-1", new BigDecimal("100.00"), "cc-topup");

        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger successCount = new AtomicInteger();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < n; i++) {
            String key = "cc-withdraw-" + i; // key RIÊNG mỗi thread
            pool.submit(() -> {
                try {
                    start.await();
                    walletService.withdraw(walletId, "user-1", new BigDecimal("10.00"), key);
                    successCount.incrementAndGet();
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers phải hoàn thành trong 30s");
        pool.shutdown();

        // (a) ít nhất một withdraw thắng
        assertTrue(successCount.get() >= 1, "phải có ít nhất 1 withdraw thành công");

        // (b) mọi thất bại đều là concurrency failure có kiểm soát
        // (OptimisticLockingFailureException là con của ConcurrencyFailureException;
        //  H2 có thể trả CannotAcquireLockException dưới contention — cùng parent)
        for (Throwable t : failures) {
            assertInstanceOf(ConcurrencyFailureException.class, t,
                    "loser chỉ được fail bằng concurrency exception, gặp: " + t);
        }

        // (c) balance = 100 - 10 * successCount (không lost update, không double debit)
        BigDecimal expected = new BigDecimal("100.00")
                .subtract(new BigDecimal("10.00").multiply(BigDecimal.valueOf(successCount.get())));
        assertEquals(0, walletService.getWallet(walletId, "user-1").getBalance().compareTo(expected),
                "balance phải khớp đúng số lần withdraw thành công");

        // (d) sổ cái: đúng successCount dòng WITHDRAW, loser rollback không để lại dòng mồ côi
        long withdrawRows = txJpa.findByWalletIdOrderByCreatedAtAsc(walletId).stream()
                .filter(t -> t.getType() == WalletTransaction.Type.WITHDRAW)
                .count();
        assertEquals(successCount.get(), withdrawRows,
                "ledger WITHDRAW rows phải bằng số lần thành công (không có dòng từ transaction rollback)");
    }

    @Test
    void concurrentTopups_sameIdempotencyKey_allCallersGetWinnerTransaction() throws Exception {
        Wallet wallet = walletService.createWallet("user-1", "SameKeySam");
        long walletId = wallet.getId();
        String key = "same-key-race";
        BigDecimal amount = new BigDecimal("25.00");

        int n = 8;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        List<WalletTransaction> results = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();

        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(walletService.topup(walletId, "user-1", amount, key));
                } catch (Throwable t) {
                    failures.add(t);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers phai hoan thanh trong 30s");
        pool.shutdown();

        // (a) khong exception nao thoat ra (DIVE phai duoc recovery thanh winner replay)
        assertTrue(failures.isEmpty(), "khong caller nao duoc fail, gap: " + failures);
        assertEquals(n, results.size());
        long distinctIds = results.stream().map(WalletTransaction::id).distinct().count();
        assertEquals(1, distinctIds, "moi caller phai nhan CUNG MOT but toan (winner replay)");

        // (b) dung 1 dong ledger cho key nay
        long rows = txJpa.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals(key)).count();
        assertEquals(1, rows, "DB chi co dung 1 but toan cho key nay");

        // (c) balance chi cong MOT lan
        assertEquals(0, amount.compareTo(walletService.getWallet(walletId, "user-1").getBalance()),
                "balance phai phan anh dung MOT lan ap dung");
    }

    @Test
    void reuseIdempotencyKey_differentPayload_throwsConflict() {
        Wallet wallet = walletService.createWallet("user-1", "ConflictCindy");
        long walletId = wallet.getId();
        walletService.topup(walletId, "user-1", new BigDecimal("40.00"), "reuse-key");

        // khac amount
        assertThrows(com.vng.wallet.domain.IdempotencyKeyConflictException.class,
                () -> walletService.topup(walletId, "user-1", new BigDecimal("41.00"), "reuse-key"));
        // khac type
        assertThrows(com.vng.wallet.domain.IdempotencyKeyConflictException.class,
                () -> walletService.withdraw(walletId, "user-1", new BigDecimal("40.00"), "reuse-key"));

        assertEquals(0, new BigDecimal("40.00").compareTo(walletService.getWallet(walletId, "user-1").getBalance()),
                "balance KHONG doi khi key conflict");
    }
}
