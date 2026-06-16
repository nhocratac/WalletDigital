package com.vng.wallet;

import com.vng.wallet.application.WalletService;
import com.vng.wallet.application.WalletService.TransferResult;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WalletTransaction;
import com.vng.wallet.infrastructure.persistence.SpringDataWalletTransactionJpa;
import com.vng.wallet.support.AllowAllKycGateTestConfig;
import com.vng.wallet.support.DefaultTenantContextConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
 * SP6 Task 5 Step 1 — concurrency của transfer (optimistic {@code @Version} trên Wallet).
 *
 * <p>Hai transfer đồng thời ĐỤNG CÙNG MỘT ví nhận (A→C và B→C) — ví C bị hai luồng cùng
 * {@code topup} → đổi version. Gọi {@link WalletService#transfer} TRỰC TIẾP từ 2 thread, mỗi
 * thread một Idempotency-Key riêng (nếu trùng key, idempotency short-circuit sẽ che mất race).
 * Service KHÔNG tự retry → loser surface dưới dạng {@link ConcurrencyFailureException}
 * (GlobalExceptionHandler map → 409 ở tầng HTTP).
 *
 * <p>Bất biến tiền (HARD RULE — money-safety):
 * <ul>
 *   <li>tổng balance toàn tenant SAU == TRƯỚC (tiền chỉ đổi chủ, không mất/không nhân);</li>
 *   <li>mỗi transfer thành công = đúng 1 cặp double-entry TRANSFER_OUT/TRANSFER_IN cùng transferId;</li>
 *   <li>loser rollback sạch — KHÔNG để lại bút toán mồ côi.</li>
 * </ul>
 */
@SpringBootTest
@Import({AllowAllKycGateTestConfig.class, DefaultTenantContextConfig.class}) // mục đích: CONCURRENCY, không phải gate
class WalletTransferConcurrencyIntegrationTest {

    @Autowired WalletService walletService;
    @Autowired WalletRepository walletRepository;
    @Autowired SpringDataWalletTransactionJpa txJpa;

    @DynamicPropertySource
    static void isolatedDb(DynamicPropertyRegistry reg) {
        // DB H2 RIÊNG: transfer ghi TRANSFER_IN với idempotency_key=NULL — nếu dùng chung mem DB
        // toàn JVM, test cũ duyệt findAll().filter(getIdempotencyKey().equals(..)) sẽ NPE trên null-key.
        reg.add("spring.datasource.url",
                () -> "jdbc:h2:mem:walletdb_xfer_cc;DB_CLOSE_DELAY=-1;MODE=LEGACY");
    }

    @Test
    void concurrentTransfersHittingSameReceiver_conserveMoney_noLostOrDuplicatedFunds() throws Exception {
        // Hai người gửi khác nhau, cùng một người nhận C — C là điểm tranh chấp version.
        Wallet a = walletService.createWallet("user-A", "Alice");
        Wallet b = walletService.createWallet("user-B", "Bob");
        Wallet c = walletService.createWallet("user-C", "Carol");
        walletService.topup(a.getId(), "user-A", new BigDecimal("100.00"), "seed-a");
        walletService.topup(b.getId(), "user-B", new BigDecimal("100.00"), "seed-b");

        BigDecimal totalBefore = sumBalances(a.getId(), b.getId(), c.getId());
        assertEquals(0, new BigDecimal("200.00").compareTo(totalBefore), "tổng trước = 100 + 100 + 0");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        List<TransferResult> wins = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        AtomicInteger successCount = new AtomicInteger();

        Runnable transferAtoC = () -> attempt(() ->
                walletService.transfer(a.getId(), c.getId(), "user-A", new BigDecimal("30.00"), "xfer-a-c"),
                start, done, wins, failures, successCount);
        Runnable transferBtoC = () -> attempt(() ->
                walletService.transfer(b.getId(), c.getId(), "user-B", new BigDecimal("40.00"), "xfer-b-c"),
                start, done, wins, failures, successCount);

        pool.submit(transferAtoC);
        pool.submit(transferBtoC);
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers phải hoàn thành trong 30s");
        pool.shutdown();

        // (a) ít nhất một transfer thắng; mọi loser fail bằng concurrency exception có kiểm soát (→ 409).
        assertTrue(successCount.get() >= 1, "phải có ít nhất 1 transfer thành công");
        for (Throwable t : failures) {
            assertInstanceOf(ConcurrencyFailureException.class, t,
                    "loser chỉ được fail bằng concurrency exception (→409), gặp: " + t);
        }

        // (b) MONEY CONSERVED: tổng balance toàn tenant không đổi, bất kể bao nhiêu transfer thắng.
        BigDecimal totalAfter = sumBalances(a.getId(), b.getId(), c.getId());
        assertEquals(0, totalBefore.compareTo(totalAfter),
                "tổng balance SAU == TRƯỚC — tiền chỉ đổi chủ, không mất/không nhân");

        // (c) double-entry cân: mỗi success = 1 OUT (từ a hoặc b) + 1 IN (vào c). Lọc theo ví của
        //     CHÍNH test này (H2 in-memory dùng chung giữa các @SpringBootTest cùng JVM — không filter
        //     sẽ đếm cả rows của test khác). OUT rows == IN rows == successCount → loser rollback sạch.
        long outRows = txJpa.findByWalletIdOrderByCreatedAtAsc(a.getId()).stream()
                .filter(t -> t.getType() == WalletTransaction.Type.TRANSFER_OUT).count()
                + txJpa.findByWalletIdOrderByCreatedAtAsc(b.getId()).stream()
                .filter(t -> t.getType() == WalletTransaction.Type.TRANSFER_OUT).count();
        long inRows = txJpa.findByWalletIdOrderByCreatedAtAsc(c.getId()).stream()
                .filter(t -> t.getType() == WalletTransaction.Type.TRANSFER_IN).count();
        assertEquals(successCount.get(), outRows, "TRANSFER_OUT rows == số transfer thắng (loser rollback sạch)");
        assertEquals(outRows, inRows, "double-entry cân: mỗi OUT có đúng 1 IN");

        // (d) C nhận đúng tổng các transfer thắng (cộng dồn từng winner amount).
        BigDecimal expectedCredited = wins.stream()
                .map(TransferResult::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, expectedCredited.compareTo(walletService.getWallet(c.getId(), "user-C").getBalance()),
                "balance C == tổng các transfer thắng, không thừa không thiếu");
    }

    private interface TransferCall { TransferResult call(); }

    private void attempt(TransferCall call, CountDownLatch start, CountDownLatch done,
                         List<TransferResult> wins, List<Throwable> failures, AtomicInteger successCount) {
        try {
            start.await();
            wins.add(call.call());
            successCount.incrementAndGet();
        } catch (Throwable t) {
            failures.add(t);
        } finally {
            done.countDown();
        }
    }

    private BigDecimal sumBalances(Long... walletIds) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Long id : walletIds) {
            // findById không-scoped (TR5) — đọc balance bất kể chủ, đủ cho kiểm tra bảo toàn tổng.
            sum = sum.add(walletRepository.findById(id).orElseThrow().getBalance());
        }
        return sum;
    }
}
