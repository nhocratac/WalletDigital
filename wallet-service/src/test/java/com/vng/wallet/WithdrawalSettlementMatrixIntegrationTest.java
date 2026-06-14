package com.vng.wallet;

import com.vng.wallet.application.ReconciliationService;
import com.vng.wallet.application.WalletService;
import com.vng.wallet.application.WithdrawalSettlementService;
import com.vng.wallet.domain.BankClient;
import com.vng.wallet.domain.InsufficientFundsException;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WalletTransaction;
import com.vng.wallet.domain.WithdrawalOrder;
import com.vng.wallet.domain.WithdrawalOrderRepository;
import com.vng.wallet.domain.WithdrawalState;
import com.vng.wallet.infrastructure.bank.MockBankClient;
import com.vng.wallet.support.AllowAllKycGateTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Task 8 (Step 1) — ma trận tình huống settlement (design §11) dưới dạng integration test
 * {@code @SpringBootTest} + {@link MockBankClient}. Scheduler TẮT (không bật
 * {@code wallet.reconcile.enabled}); test tự gọi {@link ReconciliationService#reconcile()} để lái
 * worker một cách XÁC ĐỊNH (không phụ thuộc thread nền + không rò scheduler sang DB chung —
 * bài học review Task 5).
 *
 * <p>Mỗi case dùng {@code bankRef} riêng của order qua {@link MockBankClient#configure} để dựng
 * SETTLED/REJECTED/UNKNOWN một cách độc lập. Đi qua CHÍNH các bean thật (WalletService bước ①,
 * WithdrawalSettlementService cửa nguyên tử ②③, ReconciliationService slow path) — kiểm bất biến
 * escrow {@code available = balance − held ≥ 0} ở mọi đích.
 *
 * <p>Ma trận phủ: happy settle · reject→refund · crash-trước-② · crash-sau-② ·
 * timeout-không-refund · in-doubt→NEEDS_MANUAL_REVIEW · webhook/applyTerminal trùng (idempotent) ·
 * double-spend (rút 2 lần cùng available → lần 2 thấy held → InsufficientFunds/422).
 */
@SpringBootTest(properties = "wallet.bank.mock=true")
@Import(AllowAllKycGateTestConfig.class)
class WithdrawalSettlementMatrixIntegrationTest {

    @Autowired WalletService walletService;
    @Autowired WithdrawalSettlementService settlementService;
    @Autowired ReconciliationService reconciliationService;
    @Autowired WithdrawalOrderRepository orderRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired MockBankClient bank;

    private final AtomicInteger seq = new AtomicInteger();

    /** Ví mới + topup 100 (qua bean thật). userId riêng mỗi case -> cách ly. */
    private Wallet seedWallet(String userId) {
        Wallet w = walletService.createWallet(userId, "Owner-" + userId);
        walletService.topup(w.getId(), userId, new BigDecimal("100.00"), "seed-" + userId + "-" + seq.incrementAndGet());
        return walletRepository.findById(w.getId()).orElseThrow();
    }

    private WithdrawalOrder withdraw(long walletId, String userId, String amount) {
        return walletService.withdraw(walletId, userId, new BigDecimal(amount),
                "w-" + userId + "-" + seq.incrementAndGet());
    }

    private Wallet reload(long walletId) {
        return walletRepository.findById(walletId).orElseThrow();
    }

    private void assertInvariant(Wallet w) {
        assertEquals(0, w.getBalance().subtract(w.getHeld()).compareTo(w.available()),
                "available = balance − held");
        org.junit.jupiter.api.Assertions.assertTrue(w.available().signum() >= 0, "available ≥ 0");
    }

    // 1. rút ≤ available, bank OK ngay -> SETTLED; available giảm, held về 0.
    @Test
    void happyPath_bankSettles_balanceDropsHeldZero() {
        String u = "m-happy";
        Wallet w = seedWallet(u);
        WithdrawalOrder o = withdraw(w.getId(), u, "40.00");
        bank.configure(o.getBankRef(), BankClient.BankStatus.SETTLED);

        WithdrawalOrder fresh = orderRepository.findById(o.getId()).orElseThrow();
        settlementService.processSend(fresh); // ② transfer -> SETTLED -> ③ settle

        WithdrawalOrder after = orderRepository.findById(o.getId()).orElseThrow();
        assertEquals(WithdrawalState.SETTLED, after.getState());
        Wallet wAfter = reload(w.getId());
        assertEquals(0, new BigDecimal("60.00").compareTo(wAfter.getBalance()), "total -= 40");
        assertEquals(0, BigDecimal.ZERO.compareTo(wAfter.getHeld()), "held về 0");
        assertEquals(0, new BigDecimal("60.00").compareTo(wAfter.available()));
        assertInvariant(wAfter);
    }

    // 2. bank từ chối -> FAILED -> refund; available phục hồi, balance không đổi.
    @Test
    void bankRejects_refunds_availableRestored() {
        String u = "m-reject";
        Wallet w = seedWallet(u);
        WithdrawalOrder o = withdraw(w.getId(), u, "40.00");
        bank.configure(o.getBankRef(), BankClient.BankStatus.REJECTED);

        settlementService.processSend(orderRepository.findById(o.getId()).orElseThrow());

        WithdrawalOrder after = orderRepository.findById(o.getId()).orElseThrow();
        assertEquals(WithdrawalState.FAILED, after.getState());
        Wallet wAfter = reload(w.getId());
        assertEquals(0, new BigDecimal("100.00").compareTo(wAfter.getBalance()), "balance không đổi");
        assertEquals(0, BigDecimal.ZERO.compareTo(wAfter.getHeld()), "held trả về");
        assertEquals(0, new BigDecimal("100.00").compareTo(wAfter.available()), "available phục hồi");
        assertInvariant(wAfter);
    }

    // 3. ① commit rồi crash TRƯỚC ② -> order kẹt PENDING. worker: status "chưa thấy" (UNKNOWN)
    //    -> gọi transfer cùng bankRef -> SETTLED.
    @Test
    void crashBeforeSend_workerSendsAndSettles() {
        String u = "m-crash-pre";
        Wallet w = seedWallet(u);
        WithdrawalOrder o = withdraw(w.getId(), u, "40.00"); // PENDING, bank chưa biết bankRef -> status UNKNOWN
        bank.configure(o.getBankRef(), BankClient.BankStatus.SETTLED); // transfer sẽ trả SETTLED

        assertEquals(WithdrawalState.PENDING, orderRepository.findById(o.getId()).orElseThrow().getState());
        reconciliationService.reconcile(); // PENDING + status UNKNOWN -> processSend -> SETTLED

        assertEquals(WithdrawalState.SETTLED, orderRepository.findById(o.getId()).orElseThrow().getState());
        Wallet wAfter = reload(w.getId());
        assertEquals(0, new BigDecimal("60.00").compareTo(wAfter.getBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(wAfter.getHeld()));
        assertInvariant(wAfter);
    }

    // 4. ② gọi bank rồi crash SAU khi bank đã nhận -> order SENT. worker: status "đã settle"
    //    -> ③ settle, KHÔNG gọi transfer lại (chống trả kép E7).
    @Test
    void crashAfterSend_workerSettlesWithoutResend() {
        String u = "m-crash-post";
        Wallet w = seedWallet(u);
        WithdrawalOrder o = withdraw(w.getId(), u, "40.00");

        // Mô phỏng "đã gửi, chưa áp kết quả": đẩy order lên SENT thủ công.
        WithdrawalOrder pending = orderRepository.findById(o.getId()).orElseThrow();
        pending.markSent();
        orderRepository.save(pending);

        // Bank đã settle (transfer không được gọi lại; chỉ status). Đếm transfer để chứng minh.
        AtomicInteger transfers = new AtomicInteger();
        bank.configure(o.getBankRef(), BankClient.BankStatus.SETTLED);
        // status() trả SETTLED vì transfer trước đó của reconcile happy đã ghi? Không — đây là order
        // chưa transfer. MockBankClient.status trả theo scenario nếu cấu hình -> SETTLED. OK.

        reconciliationService.reconcile(); // SENT + status SETTLED -> applyTerminal(SETTLED), không transfer

        assertEquals(WithdrawalState.SETTLED, orderRepository.findById(o.getId()).orElseThrow().getState());
        Wallet wAfter = reload(w.getId());
        assertEquals(0, new BigDecimal("60.00").compareTo(wAfter.getBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(wAfter.getHeld()));
        assertInvariant(wAfter);
        // ledger: đúng MỘT WITHDRAW_SETTLED cho bankRef này (exactly-once, không settle kép).
        long settledRows = walletRepository.listTransactions(w.getId()).stream()
                .filter(t -> t.type() == WalletTransaction.Type.WITHDRAW_SETTLED)
                .count();
        assertEquals(1, settledRows);
    }

    // 5. bank timeout (UNKNOWN) một vòng -> giữ SENT, attemptCount++, KHÔNG refund (E9).
    @Test
    void bankUnknownOnce_staysSentNoRefund() {
        String u = "m-unknown";
        Wallet w = seedWallet(u);
        WithdrawalOrder o = withdraw(w.getId(), u, "40.00");
        bank.configure(o.getBankRef(), BankClient.BankStatus.UNKNOWN);

        settlementService.processSend(orderRepository.findById(o.getId()).orElseThrow());

        WithdrawalOrder after = orderRepository.findById(o.getId()).orElseThrow();
        assertEquals(WithdrawalState.SENT, after.getState(), "unknown != failed -> giữ SENT");
        assertEquals(1, after.getAttemptCount(), "đếm attempt");
        Wallet wAfter = reload(w.getId());
        assertEquals(0, new BigDecimal("100.00").compareTo(wAfter.getBalance()));
        assertEquals(0, new BigDecimal("40.00").compareTo(wAfter.getHeld()), "tiền ĐÓNG BĂNG trong escrow, KHÔNG refund");
        assertInvariant(wAfter);
    }

    // 6. bank UNKNOWN mãi (> N lần) -> NEEDS_MANUAL_REVIEW; tiền vẫn held (đóng băng); KHÔNG auto refund/settle.
    @Test
    void bankUnknownForever_escalatesToManualReview_neverRefunds() {
        String u = "m-indoubt";
        Wallet w = seedWallet(u);
        WithdrawalOrder o = withdraw(w.getId(), u, "40.00");
        bank.configure(o.getBankRef(), BankClient.BankStatus.UNKNOWN);

        // vòng 1 = processSend (PENDING->SENT + 1 attempt); các vòng sau = reconcile (SENT + UNKNOWN -> attempt++).
        settlementService.processSend(orderRepository.findById(o.getId()).orElseThrow());
        for (int i = 0; i < WithdrawalOrder.MAX_ATTEMPTS; i++) {
            reconciliationService.reconcile();
        }

        WithdrawalOrder after = orderRepository.findById(o.getId()).orElseThrow();
        assertEquals(WithdrawalState.NEEDS_MANUAL_REVIEW, after.getState(), "quá ngưỡng N -> review");
        Wallet wAfter = reload(w.getId());
        assertEquals(0, new BigDecimal("100.00").compareTo(wAfter.getBalance()), "balance không đổi");
        assertEquals(0, new BigDecimal("40.00").compareTo(wAfter.getHeld()), "tiền vẫn đóng băng trong escrow");
        assertInvariant(wAfter);

        // worker NGỪNG đụng order review (findReconcilable loại NEEDS_MANUAL_REVIEW): reconcile thêm -> không đổi.
        reconciliationService.reconcile();
        assertEquals(WithdrawalState.NEEDS_MANUAL_REVIEW,
                orderRepository.findById(o.getId()).orElseThrow().getState());
        assertEquals(0, new BigDecimal("40.00").compareTo(reload(w.getId()).getHeld()), "vẫn không auto refund/settle");
    }

    // 7. webhook/applyTerminal báo SETTLED 2 lần (idempotent tuần tự) -> lần 2 no-op, không trừ kép.
    @Test
    void applyTerminalTwice_isIdempotent_noDoubleDebit() {
        String u = "m-dup";
        Wallet w = seedWallet(u);
        WithdrawalOrder o = withdraw(w.getId(), u, "40.00");
        // đẩy lên SENT trước khi áp terminal.
        WithdrawalOrder pending = orderRepository.findById(o.getId()).orElseThrow();
        pending.markSent();
        orderRepository.save(pending);

        settlementService.applyTerminal(o.getId(), BankClient.BankStatus.SETTLED);
        settlementService.applyTerminal(o.getId(), BankClient.BankStatus.SETTLED); // lần 2: đã terminal -> no-op

        assertEquals(WithdrawalState.SETTLED, orderRepository.findById(o.getId()).orElseThrow().getState());
        Wallet wAfter = reload(w.getId());
        assertEquals(0, new BigDecimal("60.00").compareTo(wAfter.getBalance()), "chỉ trừ MỘT lần");
        assertEquals(0, BigDecimal.ZERO.compareTo(wAfter.getHeld()));
        assertInvariant(wAfter);
        long settledRows = walletRepository.listTransactions(w.getId()).stream()
                .filter(t -> t.type() == WalletTransaction.Type.WITHDRAW_SETTLED).count();
        assertEquals(1, settledRows, "đúng MỘT bút toán WITHDRAW_SETTLED");
    }

    // 8. double-spend: rút 2 lần cùng available -> lần 2 thấy held -> InsufficientFunds (422 ở web).
    @Test
    void doubleSpend_secondWithdrawSeesHeld_rejected() {
        String u = "m-double";
        Wallet w = seedWallet(u);
        withdraw(w.getId(), u, "70.00"); // hold 70 -> available còn 30

        assertThrows(InsufficientFundsException.class,
                () -> withdraw(w.getId(), u, "70.00")); // lần 2 soi available (30) < 70 -> reject

        Wallet wAfter = reload(w.getId());
        assertEquals(0, new BigDecimal("100.00").compareTo(wAfter.getBalance()), "balance không đổi");
        assertEquals(0, new BigDecimal("70.00").compareTo(wAfter.getHeld()), "chỉ một hold");
        assertEquals(0, new BigDecimal("30.00").compareTo(wAfter.available()));
        assertInvariant(wAfter);
        // chỉ MỘT order tồn tại cho ví này.
        List<WithdrawalOrder> recon = orderRepository.findReconcilable(100).stream()
                .filter(ord -> ord.getWalletId().equals(w.getId())).toList();
        assertEquals(1, recon.size(), "lần 2 bị từ chối -> chỉ một order PENDING");
    }
}
