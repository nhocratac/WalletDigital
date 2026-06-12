package com.vng.wallet.application;

import com.vng.wallet.domain.IdempotencyKeyConflictException;
import com.vng.wallet.domain.InsufficientFundsException;
import com.vng.wallet.domain.KycGate;
import com.vng.wallet.domain.KycNotApprovedException;
import com.vng.wallet.domain.KycUnavailableException;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletNotFoundException;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WalletTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class WalletServiceTest {

    /** Fake repository — cài port bằng HashMap, KHÔNG cần DB thật. */
    static class InMemoryWalletRepository implements WalletRepository {
        private final Map<Long, Wallet> store = new HashMap<>();
        private final AtomicLong seq = new AtomicLong(0);

        @Override
        public Wallet save(Wallet wallet) {
            Long id = wallet.getId() != null ? wallet.getId() : seq.incrementAndGet();
            Wallet saved = new Wallet(id, wallet.getUserId(), wallet.getOwnerName(), wallet.getBalance(), wallet.getVersion());
            store.put(id, saved);
            return saved;
        }

        @Override
        public Optional<Wallet> findByIdAndUserId(Long id, String userId) {
            return Optional.ofNullable(store.get(id))
                    .filter(w -> userId != null && userId.equals(w.getUserId()));
        }

        @Override
        public List<Wallet> findAllByUserId(String userId) {
            return store.values().stream()
                    .filter(w -> userId != null && userId.equals(w.getUserId())).toList();
        }

        @Override
        public List<WalletTransaction> findWithdrawalsForUserSince(String userId, java.time.Instant since) {
            List<Long> ids = findAllByUserId(userId).stream().map(Wallet::getId).toList();
            return transactions.stream()
                    .filter(t -> ids.contains(t.walletId()))
                    .filter(t -> t.type() == WalletTransaction.Type.WITHDRAW)
                    .filter(t -> !t.createdAt().isBefore(since))
                    .toList();
        }

        @Override
        public WalletTransaction saveTransaction(WalletTransaction transaction) {
            WalletTransaction saved = new WalletTransaction(
                    transaction.id() != null ? transaction.id() : txSeq.incrementAndGet(),
                    transaction.walletId(), transaction.type(), transaction.amount(),
                    transaction.idempotencyKey(), transaction.balanceAfter(), transaction.createdAt());
            transactions.add(saved);
            byIdempotencyKey.put(saved.idempotencyKey(), saved);
            return saved;
        }

        @Override
        public Optional<WalletTransaction> findTransactionByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(byIdempotencyKey.get(idempotencyKey));
        }

        @Override
        public List<WalletTransaction> listTransactions(Long walletId) {
            return transactions.stream().filter(t -> t.walletId().equals(walletId)).toList();
        }

        private final List<WalletTransaction> transactions = new ArrayList<>();
        private final Map<String, WalletTransaction> byIdempotencyKey = new HashMap<>();
        private final AtomicLong txSeq = new AtomicLong(0);
    }

    /** No-op PlatformTransactionManager — giữ unit test Spring-context-free. */
    static class NoopTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }

    /** Fake gate điều khiển được — đếm số lần gọi để chốt hợp đồng "replay không đụng gate". */
    static class FakeKycGate implements KycGate {
        KycCheckResult next = new KycCheckResult(Decision.ALLOWED, "APPROVED");
        int calls = 0;
        @Override
        public KycCheckResult check(String userId) { calls++; return next; }
    }

    private final FakeKycGate gate = new FakeKycGate();
    private final WalletService service = new WalletService(
            new InMemoryWalletRepository(), new TransactionTemplate(new NoopTransactionManager()), gate);

    @Test
    void createWallet_savesWithZeroBalanceAndId() {
        Wallet created = service.createWallet("user-1", "Alice");

        assertNotNull(created.getId(), "sau khi lưu phải có id");
        assertEquals("Alice", created.getOwnerName());
        assertEquals(0, BigDecimal.ZERO.compareTo(created.getBalance()));
    }

    @Test
    void getWallet_returnsSavedWallet() {
        Wallet created = service.createWallet("user-1", "Bob");

        Wallet found = service.getWallet(created.getId(), "user-1");

        assertEquals(created.getId(), found.getId());
        assertEquals("Bob", found.getOwnerName());
        assertEquals(0, BigDecimal.ZERO.compareTo(found.getBalance()));
    }

    @Test
    void getWallet_throwsWhenMissing() {
        assertThrows(WalletNotFoundException.class, () -> service.getWallet(999L, "user-1"));
    }

    @Test
    void topup_appendsLedgerAndUpdatesBalance() {
        Wallet w = service.createWallet("user-1", "Alice");

        WalletTransaction tx = service.topup(w.getId(), "user-1", new BigDecimal("50.00"), "key-1");

        assertEquals(WalletTransaction.Type.TOPUP, tx.type());
        assertEquals(0, new BigDecimal("50.00").compareTo(tx.balanceAfter()));
        assertEquals(0, new BigDecimal("50.00").compareTo(service.getWallet(w.getId(), "user-1").getBalance()));
        assertEquals(1, service.listTransactions(w.getId(), "user-1").size());
    }

    @Test
    void topup_sameIdempotencyKeyTwice_appliesOnce() {
        Wallet w = service.createWallet("user-1", "Alice");
        WalletTransaction first = service.topup(w.getId(), "user-1", new BigDecimal("50.00"), "key-dup");

        WalletTransaction second = service.topup(w.getId(), "user-1", new BigDecimal("50.00"), "key-dup");

        assertEquals(first.id(), second.id(), "trả lại bút toán CŨ, không tạo mới");
        assertEquals(0, new BigDecimal("50.00").compareTo(service.getWallet(w.getId(), "user-1").getBalance()),
                "balance chỉ cộng MỘT lần");
        assertEquals(1, service.listTransactions(w.getId(), "user-1").size());
    }

    @Test
    void withdraw_appendsLedger() {
        Wallet w = service.createWallet("user-1", "Bob");
        service.topup(w.getId(), "user-1", new BigDecimal("100.00"), "k1");

        WalletTransaction tx = service.withdraw(w.getId(), "user-1", new BigDecimal("30.00"), "k2");

        assertEquals(WalletTransaction.Type.WITHDRAW, tx.type());
        assertEquals(0, new BigDecimal("70.00").compareTo(tx.balanceAfter()));
        assertEquals(2, service.listTransactions(w.getId(), "user-1").size());
    }

    @Test
    void withdraw_sameIdempotencyKeyTwice_appliesOnce() {
        Wallet w = service.createWallet("user-1", "Bob");
        service.topup(w.getId(), "user-1", new BigDecimal("100.00"), "k-setup");
        WalletTransaction first = service.withdraw(w.getId(), "user-1", new BigDecimal("30.00"), "w-dup");

        WalletTransaction second = service.withdraw(w.getId(), "user-1", new BigDecimal("30.00"), "w-dup");

        assertEquals(first.id(), second.id(), "retry tra lai but toan CU, khong tao moi");
        assertEquals(0, new BigDecimal("70.00").compareTo(second.balanceAfter()), "tra ve balanceAfter cua lan dau");
        assertEquals(0, new BigDecimal("70.00").compareTo(service.getWallet(w.getId(), "user-1").getBalance()), "balance chi tru MOT lan");
        assertEquals(2, service.listTransactions(w.getId(), "user-1").size(), "1 topup + 1 withdraw");
    }

    @Test
    void sameIdempotencyKey_differentPayload_throwsConflictAndBalanceUnchanged() {
        Wallet w = service.createWallet("user-1", "Alice");
        Wallet other = service.createWallet("user-1", "Mallory");
        service.topup(w.getId(), "user-1", new BigDecimal("50.00"), "key-mix");

        // khác amount
        assertThrows(IdempotencyKeyConflictException.class,
                () -> service.topup(w.getId(), "user-1", new BigDecimal("60.00"), "key-mix"));
        // khác type
        assertThrows(IdempotencyKeyConflictException.class,
                () -> service.withdraw(w.getId(), "user-1", new BigDecimal("50.00"), "key-mix"));
        // khác wallet
        assertThrows(IdempotencyKeyConflictException.class,
                () -> service.topup(other.getId(), "user-1", new BigDecimal("50.00"), "key-mix"));

        assertEquals(0, new BigDecimal("50.00").compareTo(service.getWallet(w.getId(), "user-1").getBalance()),
                "balance KHONG doi khi key bi conflict");
        assertEquals(0, BigDecimal.ZERO.compareTo(service.getWallet(other.getId(), "user-1").getBalance()));
        assertEquals(1, service.listTransactions(w.getId(), "user-1").size());
    }

    @Test
    void sameIdempotencyKey_sameAmountDifferentScale_stillReplays() {
        Wallet w = service.createWallet("user-1", "Alice");
        WalletTransaction first = service.topup(w.getId(), "user-1", new BigDecimal("100"), "key-scale");

        WalletTransaction second = service.topup(w.getId(), "user-1", new BigDecimal("100.00"), "key-scale");

        assertEquals(first.id(), second.id(), "100 vs 100.00 la cung mot retry (compareTo semantics)");
        assertEquals(0, new BigDecimal("100").compareTo(service.getWallet(w.getId(), "user-1").getBalance()));
        assertEquals(1, service.listTransactions(w.getId(), "user-1").size());
    }

    @Test
    void moneyOperations_blankIdempotencyKey_throws() {
        Wallet w = service.createWallet("user-1", "Alice");
        service.topup(w.getId(), "user-1", new BigDecimal("10.00"), "key-fund");

        assertThrows(IllegalArgumentException.class, () -> service.topup(w.getId(), "user-1", BigDecimal.ONE, ""));
        assertThrows(IllegalArgumentException.class, () -> service.topup(w.getId(), "user-1", BigDecimal.ONE, null));
        assertThrows(IllegalArgumentException.class, () -> service.topup(w.getId(), "user-1", BigDecimal.ONE, "  "));
        assertThrows(IllegalArgumentException.class, () -> service.withdraw(w.getId(), "user-1", BigDecimal.ONE, ""));
        assertThrows(IllegalArgumentException.class, () -> service.withdraw(w.getId(), "user-1", BigDecimal.ONE, null));
        assertThrows(IllegalArgumentException.class, () -> service.withdraw(w.getId(), "user-1", BigDecimal.ONE, "  "));
    }

    @Test
    void withdraw_kycDenied_throws403TypeAndNoLedger() {
        Wallet w = service.createWallet("user-1", "Alice");
        service.topup(w.getId(), "user-1", new BigDecimal("100"), "k1");
        gate.next = new KycGate.KycCheckResult(KycGate.Decision.DENIED, "PENDING");

        assertThrows(KycNotApprovedException.class,
                () -> service.withdraw(w.getId(), "user-1", new BigDecimal("10"), "k2"));
        assertEquals(1, service.listTransactions(w.getId(), "user-1").size(), "không có bút toán WITHDRAW");
    }

    @Test
    void withdraw_kycUnavailable_throws503Type() {
        Wallet w = service.createWallet("user-1", "Alice");
        gate.next = new KycGate.KycCheckResult(KycGate.Decision.UNAVAILABLE, null);
        assertThrows(KycUnavailableException.class,
                () -> service.withdraw(w.getId(), "user-1", new BigDecimal("1"), "k3"));
    }

    @Test
    void withdraw_idempotentReplay_skipsKycGate() {
        Wallet w = service.createWallet("user-1", "Alice");
        service.topup(w.getId(), "user-1", new BigDecimal("100"), "kt");
        service.withdraw(w.getId(), "user-1", new BigDecimal("10"), "kw");
        gate.calls = 0;
        gate.next = new KycGate.KycCheckResult(KycGate.Decision.DENIED, "REVOKED"); // dù giờ bị deny...

        WalletTransaction replay = service.withdraw(w.getId(), "user-1", new BigDecimal("10"), "kw");

        assertEquals(0, gate.calls, "replay KHÔNG gọi gate — trả kết quả CŨ (đúng ngữ nghĩa idempotent)");
        assertEquals(0, new BigDecimal("10").compareTo(replay.amount()));
    }

    @Test
    void topup_neverCallsKycGate() {
        Wallet w = service.createWallet("user-1", "Alice");
        gate.calls = 0;
        service.topup(w.getId(), "user-1", new BigDecimal("5"), "kx");
        assertEquals(0, gate.calls, "R1: nạp tiền tự do, không gác");
    }

    @Test
    void withdraw_insufficient_throwsAndNoLedgerEntry() {
        Wallet w = service.createWallet("user-1", "Carol");

        assertThrows(InsufficientFundsException.class,
                () -> service.withdraw(w.getId(), "user-1", new BigDecimal("1.00"), "k3"));
        assertEquals(0, service.listTransactions(w.getId(), "user-1").size(), "thất bại -> KHÔNG có bút toán");
    }
}
