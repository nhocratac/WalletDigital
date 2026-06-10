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
 * map OptimisticLockingFailureException -> 409 ở tầng HTTP.
 */
@SpringBootTest
class WalletConcurrencyIntegrationTest {

    @Autowired WalletService walletService;
    @Autowired SpringDataWalletTransactionJpa txJpa;

    @Test
    void concurrentWithdraws_noLostUpdates_andLedgerMatchesSuccesses() throws Exception {
        Wallet wallet = walletService.createWallet("ConcurrencyCarl");
        long walletId = wallet.getId();
        walletService.topup(walletId, new BigDecimal("100.00"), "cc-topup");

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
                    walletService.withdraw(walletId, new BigDecimal("10.00"), key);
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
        assertEquals(0, walletService.getWallet(walletId).getBalance().compareTo(expected),
                "balance phải khớp đúng số lần withdraw thành công");

        // (d) sổ cái: đúng successCount dòng WITHDRAW, loser rollback không để lại dòng mồ côi
        long withdrawRows = txJpa.findByWalletIdOrderByCreatedAtAsc(walletId).stream()
                .filter(t -> t.getType() == WalletTransaction.Type.WITHDRAW)
                .count();
        assertEquals(successCount.get(), withdrawRows,
                "ledger WITHDRAW rows phải bằng số lần thành công (không có dòng từ transaction rollback)");
    }
}
