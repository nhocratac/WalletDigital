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
 * Task 4: cửa nguyên tử {@code applyTerminal} (CAS dưới @Version) + {@code processSend}.
 * Fake bank + fake repo có optimistic-lock semantics (save dưới version cũ -> OLE).
 */
class WithdrawalSettlementServiceTest {

    /** Fake wallet repo — đủ cho settle/release + ledger; findById KHÔNG scoped (worker/webhook). */
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

    /** Fake order repo — save dưới @Version: lưu version cũ != version hiện tại trong store -> OLE. */
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

    static class FakeBank implements BankClient {
        BankStatus transferResult = BankStatus.SETTLED;
        BankStatus statusResult = BankStatus.SETTLED;
        int transferCalls = 0;
        @Override public TransferAck transfer(String bankRef, BigDecimal amount) {
            transferCalls++;
            return new TransferAck(transferResult);
        }
        @Override public BankStatus status(String bankRef) { return statusResult; }
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
    private final WithdrawalSettlementService service = new WithdrawalSettlementService(
            orders, wallets, bank, new TransactionTemplate(new NoopTxManager()));

    /** Ví đã hold sẵn 30 (sau bước ①): balance 100, held 30, available 70. */
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
    void processSend_settled_settlesWalletAndAppendsSettledLedger() {
        Wallet w = seedWalletHeld30();
        WithdrawalOrder o = seedOrder(w.getId(), WithdrawalState.PENDING);
        bank.transferResult = BankClient.BankStatus.SETTLED;

        service.processSend(o);

        WithdrawalOrder after = orders.findById(o.getId()).orElseThrow();
        assertEquals(WithdrawalState.SETTLED, after.getState());
        Wallet wAfter = wallets.findById(w.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("70").compareTo(wAfter.getBalance()), "balance -= amount (tiền rời hệ)");
        assertEquals(0, BigDecimal.ZERO.compareTo(wAfter.getHeld()), "held -= amount");
        assertEquals(0, new BigDecimal("70").compareTo(wAfter.available()), "available không đổi");
        assertEquals(1, wallets.transactions.stream()
                .filter(t -> t.type() == WalletTransaction.Type.WITHDRAW_SETTLED).count());
    }

    @Test
    void processSend_rejected_refundsWalletAndAppendsRefundLedger() {
        Wallet w = seedWalletHeld30();
        WithdrawalOrder o = seedOrder(w.getId(), WithdrawalState.PENDING);
        bank.transferResult = BankClient.BankStatus.REJECTED;

        service.processSend(o);

        WithdrawalOrder after = orders.findById(o.getId()).orElseThrow();
        assertEquals(WithdrawalState.FAILED, after.getState());
        Wallet wAfter = wallets.findById(w.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("100").compareTo(wAfter.getBalance()), "balance không đổi");
        assertEquals(0, BigDecimal.ZERO.compareTo(wAfter.getHeld()), "held trả về 0");
        assertEquals(0, new BigDecimal("100").compareTo(wAfter.available()), "available phục hồi");
        assertEquals(1, wallets.transactions.stream()
                .filter(t -> t.type() == WalletTransaction.Type.WITHDRAW_REFUNDED).count());
    }

    @Test
    void processSend_unknown_staysSentRecordsAttempt_noTerminal() {
        Wallet w = seedWalletHeld30();
        WithdrawalOrder o = seedOrder(w.getId(), WithdrawalState.PENDING);
        bank.transferResult = BankClient.BankStatus.UNKNOWN;

        service.processSend(o);

        WithdrawalOrder after = orders.findById(o.getId()).orElseThrow();
        assertEquals(WithdrawalState.SENT, after.getState(), "UNKNOWN != failed (E9) -> giữ SENT");
        assertEquals(1, after.getAttemptCount(), "recordUnknownAttempt");
        Wallet wAfter = wallets.findById(w.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("100").compareTo(wAfter.getBalance()), "KHÔNG settle");
        assertEquals(0, new BigDecimal("30").compareTo(wAfter.getHeld()), "KHÔNG refund — tiền vẫn held");
        assertTrue(wallets.transactions.isEmpty(), "không ghi ledger terminal");
    }

    @Test
    void applyTerminal_twiceSequentially_isNoOpSecondTime() {
        Wallet w = seedWalletHeld30();
        WithdrawalOrder o = seedOrder(w.getId(), WithdrawalState.SENT);

        service.applyTerminal(o.getId(), BankClient.BankStatus.REJECTED);
        service.applyTerminal(o.getId(), BankClient.BankStatus.REJECTED); // lần 2 thấy terminal -> no-op

        Wallet wAfter = wallets.findById(w.getId()).orElseThrow();
        assertEquals(0, new BigDecimal("100").compareTo(wAfter.getBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(wAfter.getHeld()), "refund chỉ MỘT lần");
        assertEquals(1, wallets.transactions.stream()
                .filter(t -> t.type() == WalletTransaction.Type.WITHDRAW_REFUNDED).count(),
                "ledger refund đúng MỘT bút toán");
    }

    @Test
    void applyTerminal_concurrentRace_appliesExactlyOnce() {
        Wallet w = seedWalletHeld30();
        WithdrawalOrder o = seedOrder(w.getId(), WithdrawalState.SENT);

        // Hai actor (worker × webhook) cùng đọc order ở version V rồi áp tuần tự.
        // applyTerminal reload mỗi lần; nhưng để mô phỏng đua thực, ta gọi lần 1 (commit V->V+1),
        // rồi gọi applyTerminalOn(staleOrder) với instance version cũ -> save đụng OLE -> nuốt.
        WithdrawalOrder stale = orders.findById(o.getId()).orElseThrow(); // version V

        service.applyTerminal(o.getId(), BankClient.BankStatus.REJECTED);  // người thắng

        // người thua: dùng instance cũ (version V) đi qua cửa, nhưng order trong store đã V+1
        service.applyTerminalOn(stale, BankClient.BankStatus.REJECTED);    // nuốt OLE, không áp lần 2

        Wallet wAfter = wallets.findById(w.getId()).orElseThrow();
        assertEquals(0, BigDecimal.ZERO.compareTo(wAfter.getHeld()), "held đổi đúng MỘT lần");
        assertEquals(0, new BigDecimal("100").compareTo(wAfter.available()), "available phục hồi đúng MỘT lần");
        assertEquals(1, wallets.transactions.stream()
                .filter(t -> t.type() == WalletTransaction.Type.WITHDRAW_REFUNDED).count());
    }
}
