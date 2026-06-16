package com.vng.wallet.application;

import com.vng.wallet.domain.BankClient;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WalletTransaction;
import com.vng.wallet.domain.WithdrawalOrder;
import com.vng.wallet.domain.WithdrawalOrderRepository;
import com.vng.wallet.domain.WithdrawalState;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 5: reconciliation worker self-healing (E6, E8). Service tách khỏi scheduler để test
 * không phụ thuộc thời gian. Fake bank (cấu hình transfer/status riêng) + in-memory repo có
 * optimistic-lock semantics (save dưới version cũ -> OLE).
 */
class ReconciliationServiceTest {

    static class InMemoryWalletRepository implements WalletRepository {
        final Map<Long, Wallet> store = new HashMap<>();
        final List<WalletTransaction> transactions = new ArrayList<>();
        final AtomicLong seq = new AtomicLong(0);
        final AtomicLong txSeq = new AtomicLong(0);

        @Override
        public Wallet save(Wallet wallet) {
            Long id = wallet.getId() != null ? wallet.getId() : seq.incrementAndGet();
            Long v = wallet.getVersion() == null ? 0L : wallet.getVersion() + 1;
            Wallet saved = new Wallet(id, wallet.getUserId(), wallet.getOwnerName(),
                    wallet.getBalance(), wallet.getHeld(), v);
            store.put(id, saved);
            return saved;
        }

        @Override public Optional<Wallet> findById(Long id) { return Optional.ofNullable(store.get(id)); }
        @Override public Optional<Wallet> findByIdAndUserId(Long id, String userId) {
            return findById(id).filter(w -> userId != null && userId.equals(w.getUserId()));
        }
        @Override public List<Wallet> findAllByUserId(String userId) { return List.of(); }
        @Override public List<WalletTransaction> findWithdrawalsForUserSince(String u, Instant s) { return List.of(); }
        @Override public WalletTransaction saveTransaction(WalletTransaction t) {
            WalletTransaction saved = new WalletTransaction(txSeq.incrementAndGet(), t.walletId(), t.type(),
                    t.amount(), t.idempotencyKey(), t.balanceAfter(), t.createdAt());
            transactions.add(saved);
            return saved;
        }
        @Override public Optional<WalletTransaction> findTransactionByIdempotencyKey(String k) { return Optional.empty(); }
        @Override public Optional<WalletTransaction> findTransactionByTransferIdAndType(String t, WalletTransaction.Type type) { return Optional.empty(); }
        @Override public List<WalletTransaction> listTransactions(Long walletId) {
            return transactions.stream().filter(t -> t.walletId().equals(walletId)).toList();
        }
    }

    static class InMemoryOrderRepository implements WithdrawalOrderRepository {
        final Map<Long, WithdrawalOrder> store = new HashMap<>();
        final AtomicLong seq = new AtomicLong(0);

        @Override
        public WithdrawalOrder save(WithdrawalOrder order) {
            if (order.getId() != null) {
                WithdrawalOrder current = store.get(order.getId());
                long incoming = order.getVersion() == null ? 0L : order.getVersion();
                long persisted = current == null || current.getVersion() == null ? 0L : current.getVersion();
                if (incoming != persisted) {
                    throw new ObjectOptimisticLockingFailureException(WithdrawalOrder.class, order.getId());
                }
            }
            Long id = order.getId() != null ? order.getId() : seq.incrementAndGet();
            long v = (order.getVersion() == null ? 0L : order.getVersion()) + 1;
            WithdrawalOrder saved = new WithdrawalOrder(id, order.getUserId(), order.getWalletId(),
                    order.getAmount(), order.getState(), order.getBankRef(), order.getIdempotencyKey(),
                    order.getAttemptCount(), order.getFirstSentAt(), v);
            store.put(id, saved);
            return saved;
        }

        @Override public Optional<WithdrawalOrder> findById(Long id) { return Optional.ofNullable(store.get(id)); }
        @Override public Optional<WithdrawalOrder> findByIdempotencyKey(String k) {
            return store.values().stream().filter(o -> k.equals(o.getIdempotencyKey())).findFirst();
        }
        @Override public Optional<WithdrawalOrder> findByBankRef(String r) {
            return store.values().stream().filter(o -> r.equals(o.getBankRef())).findFirst();
        }
        @Override public Optional<WithdrawalOrder> findByIdAndUserId(Long id, String userId) {
            return findById(id).filter(o -> userId != null && userId.equals(o.getUserId()));
        }
        @Override public List<WithdrawalOrder> findReconcilable(int limit) {
            return store.values().stream()
                    .filter(o -> o.getState() == WithdrawalState.PENDING || o.getState() == WithdrawalState.SENT)
                    .limit(limit).toList();
        }
    }

    /** Bank cấu hình tách biệt transfer-result và status-result; đếm cú gọi. */
    static class FakeBank implements BankClient {
        BankStatus transferResult = BankStatus.SETTLED;
        BankStatus statusResult = BankStatus.UNKNOWN;
        int transferCalls = 0;
        int statusCalls = 0;
        @Override public TransferAck transfer(String bankRef, BigDecimal amount) {
            transferCalls++;
            return new TransferAck(transferResult);
        }
        @Override public BankStatus status(String bankRef) {
            statusCalls++;
            return statusResult;
        }
    }

    static class NoopTxManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object t, TransactionDefinition d) {}
        @Override protected void doCommit(DefaultTransactionStatus s) {}
        @Override protected void doRollback(DefaultTransactionStatus s) {}
    }

    private final InMemoryWalletRepository wallets = new InMemoryWalletRepository();
    private final InMemoryOrderRepository orders = new InMemoryOrderRepository();
    private final FakeBank bank = new FakeBank();
    private final TransactionTemplate tx = new TransactionTemplate(new NoopTxManager());
    private final WithdrawalSettlementService settlement =
            new WithdrawalSettlementService(orders, wallets, bank, tx);
    private final ReconciliationService service =
            new ReconciliationService(orders, bank, settlement, 100);

    private Wallet seedWalletHeld30() {
        return wallets.save(new Wallet(null, "user-1", "Alice",
                new BigDecimal("100"), new BigDecimal("30"), null));
    }

    private WithdrawalOrder seedOrder(Long walletId, WithdrawalState state) {
        WithdrawalOrder o = new WithdrawalOrder(null, "user-1", walletId, new BigDecimal("30"),
                state, "wd-ref-1", "k1", 0, state == WithdrawalState.PENDING ? null : Instant.now(), null);
        return orders.save(o);
    }

    @Test
    void pendingCrashBeforeSend_statusUnknown_sendsViaSameBankRef_settles() {
        // Crash trước ②: order PENDING, bank "chưa thấy lệnh" (status UNKNOWN) -> gọi transfer cùng bankRef -> SETTLED.
        Wallet w = seedWalletHeld30();
        WithdrawalOrder o = seedOrder(w.getId(), WithdrawalState.PENDING);
        bank.statusResult = BankClient.BankStatus.UNKNOWN; // bank chưa thấy
        bank.transferResult = BankClient.BankStatus.SETTLED;

        service.reconcile();

        WithdrawalOrder after = orders.findById(o.getId()).orElseThrow();
        assertEquals(WithdrawalState.SETTLED, after.getState());
        assertEquals(1, bank.transferCalls, "PENDING chưa gửi -> gọi transfer cùng bankRef");
        Wallet wAfter = wallets.findById(w.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("70").compareTo(wAfter.getBalance()), "đã settle (tiền rời hệ)");
        assertEquals(0, BigDecimal.ZERO.compareTo(wAfter.getHeld()));
    }

    @Test
    void sentCrashAfterSend_statusSettled_settlesWithoutResend() {
        // Crash sau khi bank đã nhận: order SENT, status=SETTLED -> ③ settle, KHÔNG gọi transfer lại (chống trả kép E7).
        Wallet w = seedWalletHeld30();
        WithdrawalOrder o = seedOrder(w.getId(), WithdrawalState.SENT);
        bank.statusResult = BankClient.BankStatus.SETTLED;

        service.reconcile();

        WithdrawalOrder after = orders.findById(o.getId()).orElseThrow();
        assertEquals(WithdrawalState.SETTLED, after.getState());
        assertEquals(0, bank.transferCalls, "SENT -> KHÔNG gọi transfer lại");
        assertEquals(1, bank.statusCalls, "SENT -> query status");
        Wallet wAfter = wallets.findById(w.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("70").compareTo(wAfter.getBalance()));
    }

    @Test
    void sentStatusRejected_refunds() {
        Wallet w = seedWalletHeld30();
        WithdrawalOrder o = seedOrder(w.getId(), WithdrawalState.SENT);
        bank.statusResult = BankClient.BankStatus.REJECTED;

        service.reconcile();

        WithdrawalOrder after = orders.findById(o.getId()).orElseThrow();
        assertEquals(WithdrawalState.FAILED, after.getState());
        Wallet wAfter = wallets.findById(w.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("100").compareTo(wAfter.getBalance()), "balance không đổi");
        assertEquals(0, BigDecimal.ZERO.compareTo(wAfter.getHeld()), "refund -> held 0");
        assertEquals(1, wallets.transactions.stream()
                .filter(t -> t.type() == WalletTransaction.Type.WITHDRAW_REFUNDED).count());
    }

    @Test
    void sentStatusUnknown_staysSent_incrementsAttempt_noMoneyChange() {
        // "unknown != failed" (E9): giữ SENT, attemptCount++, KHÔNG refund/settle.
        Wallet w = seedWalletHeld30();
        WithdrawalOrder o = seedOrder(w.getId(), WithdrawalState.SENT);
        bank.statusResult = BankClient.BankStatus.UNKNOWN;

        service.reconcile();

        WithdrawalOrder after = orders.findById(o.getId()).orElseThrow();
        assertEquals(WithdrawalState.SENT, after.getState());
        assertEquals(1, after.getAttemptCount(), "attemptCount++");
        Wallet wAfter = wallets.findById(w.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("30").compareTo(wAfter.getHeld()), "tiền vẫn held, KHÔNG đổi");
        assertTrue(wallets.transactions.isEmpty(), "không ghi ledger terminal");
        assertEquals(0, bank.transferCalls, "SENT -> không gọi transfer");
    }

    @Test
    void sentUnknownPastThreshold_escalatesToManualReview_neverRefunds() {
        // Task 6 (E9/E10): SENT đã UNKNOWN tới ngưỡng N -> NEEDS_MANUAL_REVIEW; tiền vẫn held (đóng băng);
        // KHÔNG auto refund/settle. wallet.release KHÔNG được gọi.
        Wallet w = seedWalletHeld30();
        // Order đã ở MAX_ATTEMPTS-1 lần unknown trước đó; vòng này là lần thứ N -> chạm ngưỡng.
        WithdrawalOrder o = orders.save(new WithdrawalOrder(null, "user-1", w.getId(), new BigDecimal("30"),
                WithdrawalState.SENT, "wd-ref-1", "k1",
                WithdrawalOrder.MAX_ATTEMPTS - 1, Instant.now(), null));
        bank.statusResult = BankClient.BankStatus.UNKNOWN;

        service.reconcile();

        WithdrawalOrder after = orders.findById(o.getId()).orElseThrow();
        assertEquals(WithdrawalState.NEEDS_MANUAL_REVIEW, after.getState(),
                "quá ngưỡng UNKNOWN -> NEEDS_MANUAL_REVIEW");
        assertEquals(WithdrawalOrder.MAX_ATTEMPTS, after.getAttemptCount());
        Wallet wAfter = wallets.findById(w.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("30").compareTo(wAfter.getHeld()), "tiền vẫn held (đóng băng)");
        assertEquals(0, new BigDecimal("100").compareTo(wAfter.getBalance()), "balance không đổi");
        assertTrue(wallets.transactions.isEmpty(), "KHÔNG refund/settle -> không ledger terminal");
    }

    @Test
    void manualReviewOrders_areNotReconciled() {
        // findReconcilable KHÔNG trả NEEDS_MANUAL_REVIEW -> worker ngừng đụng (chờ người).
        Wallet w = seedWalletHeld30();
        seedOrder(w.getId(), WithdrawalState.NEEDS_MANUAL_REVIEW);

        service.reconcile();

        assertEquals(0, bank.statusCalls, "NEEDS_MANUAL_REVIEW -> worker không đụng bank");
        assertEquals(0, bank.transferCalls);
    }

    @Test
    void terminalOrders_areSkipped() {
        // findReconcilable không trả SETTLED/FAILED/NEEDS_MANUAL_REVIEW -> worker bỏ qua.
        Wallet w = seedWalletHeld30();
        seedOrder(w.getId(), WithdrawalState.SETTLED);
        seedOrder(w.getId(), WithdrawalState.FAILED);

        service.reconcile();

        assertEquals(0, bank.transferCalls);
        assertEquals(0, bank.statusCalls, "terminal -> không đụng bank");
    }

    @Test
    void twoWorkersOnSameOrder_appliesExactlyOnce() {
        // Hai worker đua trên cùng order SENT: chỉ một thắng (@Version), người thua nuốt OLE.
        Wallet w = seedWalletHeld30();
        WithdrawalOrder o = seedOrder(w.getId(), WithdrawalState.SENT);
        bank.statusResult = BankClient.BankStatus.REJECTED;

        // worker 1 reconcile xong (commit V->V+1), worker 2 dùng instance order stale (version V).
        WithdrawalOrder stale = orders.findById(o.getId()).orElseThrow();
        service.reconcile(); // người thắng

        // người thua đi qua cửa với instance cũ -> save đụng OLE -> nuốt, không áp lần 2
        settlement.applyTerminalOn(stale, BankClient.BankStatus.REJECTED);

        Wallet wAfter = wallets.findById(w.getId()).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(wAfter.getHeld()), "refund đúng MỘT lần");
        assertEquals(1, wallets.transactions.stream()
                .filter(t -> t.type() == WalletTransaction.Type.WITHDRAW_REFUNDED).count());
    }

    @Test
    void oneOrderFailure_doesNotBlockOthers() {
        // Bọc mỗi order trong try/catch: một order ném lỗi không chặn order khác.
        Wallet w = seedWalletHeld30();
        WithdrawalOrder bad = seedOrder(w.getId(), WithdrawalState.SENT);
        // Bank ném runtime khi query order "bad" nhưng order good vẫn được xử.
        Wallet w2 = wallets.save(new Wallet(null, "user-2", "Bob",
                new BigDecimal("100"), new BigDecimal("30"), null));
        WithdrawalOrder good = new WithdrawalOrder(null, "user-2", w2.getId(), new BigDecimal("30"),
                WithdrawalState.SENT, "wd-ref-good", "k2", 0, Instant.now(), null);
        good = orders.save(good);

        FakeBank throwingBank = new FakeBank() {
            @Override public BankStatus status(String bankRef) {
                if ("wd-ref-1".equals(bankRef)) throw new RuntimeException("boom");
                return BankStatus.SETTLED;
            }
        };
        WithdrawalSettlementService settlement2 =
                new WithdrawalSettlementService(orders, wallets, throwingBank, tx);
        ReconciliationService svc2 = new ReconciliationService(orders, throwingBank, settlement2, 100);

        svc2.reconcile(); // bad ném -> nuốt; good vẫn settle

        assertEquals(WithdrawalState.SETTLED, orders.findById(good.getId()).orElseThrow().getState());
        assertEquals(WithdrawalState.SENT, orders.findById(bad.getId()).orElseThrow().getState(),
                "order lỗi giữ nguyên SENT, không chặn order khác");
    }
}
