package com.vng.wallet;

import com.vng.wallet.application.WalletService;
import com.vng.wallet.application.WithdrawalSettlementService;
import com.vng.wallet.domain.BankClient;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WalletTransaction;
import com.vng.wallet.domain.WithdrawalOrder;
import com.vng.wallet.domain.WithdrawalOrderRepository;
import com.vng.wallet.domain.WithdrawalState;
import com.vng.wallet.support.AllowAllKycGateTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 7 — ⭐ exactly-once đầu-cuối khi worker (slow path) × webhook (fast path) đua nhau terminal-hoá
 * CÙNG một order còn SENT.
 *
 * <p>Cả hai đường đi qua CHUNG cửa nguyên tử {@link WithdrawalSettlementService#applyTerminal} (CAS
 * dưới {@code @Version}). Mô phỏng đua thật: load HAI instance order ở cùng version V rồi gọi
 * {@code applyTerminalOn} từ hai thread. Người đầu commit (V→V+1); người thứ hai save đụng
 * {@link org.springframework.dao.OptimisticLockingFailureException} → rollback → KHÔNG đổi tiền lần 2.
 *
 * <p>Khẳng định: order về terminal đúng MỘT lần; {@code wallet.held} giảm đúng MỘT lần; ledger có
 * đúng MỘT bút toán {@code WITHDRAW_SETTLED} (không kép).
 */
@SpringBootTest
@Import(AllowAllKycGateTestConfig.class)
class WithdrawalRaceIntegrationTest {

    @Autowired WalletService walletService;
    @Autowired WithdrawalSettlementService settlementService;
    @Autowired WithdrawalOrderRepository orderRepository;
    @Autowired WalletRepository walletRepository;

    @Test
    void workerAndWebhookRace_appliesTerminalExactlyOnce() throws Exception {
        // Arrange: ví 100, rút 40 -> order PENDING + held 40, rồi đẩy lên SENT (đã gọi bank, chờ kết quả).
        Wallet wallet = walletService.createWallet("race-user", "RaceRita");
        long walletId = wallet.getId();
        walletService.topup(walletId, "race-user", new BigDecimal("100.00"), "race-topup");
        WithdrawalOrder pending = walletService.withdraw(
                walletId, "race-user", new BigDecimal("40.00"), "race-withdraw");
        long orderId = pending.getId();

        WithdrawalOrder sent = orderRepository.findById(orderId).orElseThrow();
        sent.markSent();
        orderRepository.save(sent);

        // Hai actor cùng load order ở CÙNG version V (worker reconcile + webhook fast path).
        WithdrawalOrder workerView = orderRepository.findById(orderId).orElseThrow();
        WithdrawalOrder webhookView = orderRepository.findById(orderId).orElseThrow();
        assertEquals(workerView.getVersion(), webhookView.getVersion(), "cùng version -> đua thật");

        // Act: bắn đồng thời cả hai đường, cùng outcome SETTLED, qua CHUNG applyTerminal CAS.
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger completed = new AtomicInteger();
        Runnable worker = () -> race(start, done, completed, workerView);
        Runnable webhook = () -> race(start, done, completed, webhookView);
        pool.submit(worker);
        pool.submit(webhook);
        start.countDown();
        assertTrue(done.await(20, TimeUnit.SECONDS), "cả hai actor phải hoàn thành trong 20s");
        pool.shutdown();

        // applyTerminalOn nuốt OptimisticLock của người thua -> cả hai "completed" (không ném ra ngoài).
        assertEquals(2, completed.get(), "người thua nuốt OptimisticLock, không ném ra ngoài");

        // Assert exactly-once: order SETTLED, balance giảm ĐÚNG 40 (60), held về 0, available 60.
        WithdrawalOrder after = orderRepository.findById(orderId).orElseThrow();
        assertEquals(WithdrawalState.SETTLED, after.getState());
        Wallet w = walletRepository.findById(walletId).orElseThrow();
        assertEquals(0, new BigDecimal("60.00").compareTo(w.getBalance()), "settle MỘT lần -> total 60");
        assertEquals(0, BigDecimal.ZERO.compareTo(w.getHeld()), "held về 0 (không trừ kép)");
        assertEquals(0, new BigDecimal("60.00").compareTo(w.available()));

        // Ledger: đúng MỘT bút toán WITHDRAW_SETTLED cho order này (không kép).
        List<WalletTransaction> settled = walletRepository.listTransactions(walletId).stream()
                .filter(t -> t.type() == WalletTransaction.Type.WITHDRAW_SETTLED)
                .toList();
        assertEquals(1, settled.size(), "đúng MỘT bút toán WITHDRAW_SETTLED (exactly-once)");
    }

    private void race(CountDownLatch start, CountDownLatch done, AtomicInteger completed,
                      WithdrawalOrder view) {
        try {
            start.await();
            settlementService.applyTerminalOn(view, BankClient.BankStatus.SETTLED);
            completed.incrementAndGet();
        } catch (Throwable ignored) {
            // applyTerminalOn ĐÃ nuốt loser race (OptimisticLock / ledger-unique);
            // nếu lọt ra ngoài -> test fail ở completed==2.
        } finally {
            done.countDown();
        }
    }
}
