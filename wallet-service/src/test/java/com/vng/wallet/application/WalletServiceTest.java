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
import com.vng.wallet.domain.WithdrawalOrder;
import com.vng.wallet.domain.WithdrawalOrderRepository;
import com.vng.wallet.domain.WithdrawalState;
import com.vng.wallet.idempotency.IdempotencyRecord;
import com.vng.wallet.idempotency.IdempotencyService;
import com.vng.wallet.idempotency.IdempotencyStore;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
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
            Wallet saved = new Wallet(id, wallet.getUserId(), wallet.getOwnerName(), wallet.getBalance(), wallet.getHeld(), wallet.getVersion());
            store.put(id, saved);
            return saved;
        }

        @Override
        public Optional<Wallet> findById(Long id) {
            return Optional.ofNullable(store.get(id));
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
                    .filter(t -> t.type() == WalletTransaction.Type.WITHDRAW_HOLD)
                    .filter(t -> !t.createdAt().isBefore(since))
                    .toList();
        }

        @Override
        public WalletTransaction saveTransaction(WalletTransaction transaction) {
            WalletTransaction saved = new WalletTransaction(
                    transaction.id() != null ? transaction.id() : txSeq.incrementAndGet(),
                    transaction.walletId(), transaction.type(), transaction.amount(),
                    transaction.idempotencyKey(), transaction.balanceAfter(), transaction.createdAt(),
                    transaction.transferId());
            transactions.add(saved);
            if (saved.idempotencyKey() != null) {
                byIdempotencyKey.put(saved.idempotencyKey(), saved);
            }
            return saved;
        }

        @Override
        public Optional<WalletTransaction> findTransactionByIdempotencyKey(String idempotencyKey) {
            return Optional.ofNullable(byIdempotencyKey.get(idempotencyKey));
        }

        @Override
        public Optional<WalletTransaction> findTransactionByTransferIdAndType(
                String transferId, WalletTransaction.Type type) {
            return transactions.stream()
                    .filter(t -> transferId != null && transferId.equals(t.transferId()) && t.type() == type)
                    .findFirst();
        }

        @Override
        public List<WalletTransaction> listTransactions(Long walletId) {
            return transactions.stream().filter(t -> t.walletId().equals(walletId)).toList();
        }

        private final List<WalletTransaction> transactions = new ArrayList<>();
        private final Map<String, WalletTransaction> byIdempotencyKey = new HashMap<>();
        private final AtomicLong txSeq = new AtomicLong(0);
    }

    /** Fake order repo — đủ cho use case: replay theo idempotency_key + lưu mới. */
    static class InMemoryWithdrawalOrderRepository implements WithdrawalOrderRepository {
        private final Map<Long, WithdrawalOrder> store = new HashMap<>();
        private final Map<String, WithdrawalOrder> byKey = new HashMap<>();
        private final AtomicLong seq = new AtomicLong(0);

        @Override
        public WithdrawalOrder save(WithdrawalOrder order) {
            Long id = order.getId() != null ? order.getId() : seq.incrementAndGet();
            WithdrawalOrder saved = new WithdrawalOrder(id, order.getUserId(), order.getWalletId(),
                    order.getAmount(), order.getState(), order.getBankRef(), order.getIdempotencyKey(),
                    order.getAttemptCount(), order.getFirstSentAt(), order.getVersion());
            store.put(id, saved);
            byKey.put(saved.getIdempotencyKey(), saved);
            return saved;
        }

        @Override
        public Optional<WithdrawalOrder> findById(Long id) {
            return Optional.ofNullable(store.get(id));
        }

        @Override
        public Optional<WithdrawalOrder> findByIdempotencyKey(String key) {
            return Optional.ofNullable(byKey.get(key));
        }

        @Override
        public Optional<WithdrawalOrder> findByBankRef(String bankRef) {
            return store.values().stream().filter(o -> bankRef.equals(o.getBankRef())).findFirst();
        }

        @Override
        public Optional<WithdrawalOrder> findByIdAndUserId(Long id, String userId) {
            return Optional.ofNullable(store.get(id))
                    .filter(o -> userId != null && userId.equals(o.getUserId()));
        }

        @Override
        public List<WithdrawalOrder> findReconcilable(int limit) {
            return store.values().stream()
                    .filter(o -> o.getState() == WithdrawalState.PENDING || o.getState() == WithdrawalState.SENT)
                    .limit(limit).toList();
        }
    }

    /** No-op PlatformTransactionManager — giữ unit test Spring-context-free. */
    static class NoopTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {}
        @Override protected void doRollback(DefaultTransactionStatus status) {}
    }

    /**
     * Fake idempotency store in-memory — save() là INSERT thuần (trùng key → DIVE) mô phỏng UNIQUE PK
     * của {@code idempotency_record}, để chứng minh topup/transfer giờ enforce dedup QUA bảng record
     * (SP7 Task 3) chứ không qua pre-check ledger.
     */
    static final class InMemoryIdempotencyStore implements IdempotencyStore {
        final Map<String, IdempotencyRecord> rows = new HashMap<>();

        @Override
        public Optional<IdempotencyRecord> find(String idempotencyKey) {
            return Optional.ofNullable(rows.get(idempotencyKey));
        }

        @Override
        public IdempotencyRecord save(IdempotencyRecord record) {
            if (rows.containsKey(record.idempotencyKey())) {
                throw new DataIntegrityViolationException("duplicate key " + record.idempotencyKey());
            }
            rows.put(record.idempotencyKey(), record);
            return record;
        }

        @Override
        public void updateResultRef(String idempotencyKey, String resultRef) {
            IdempotencyRecord r = rows.get(idempotencyKey);
            if (r == null) return;
            rows.put(idempotencyKey, new IdempotencyRecord(
                    r.idempotencyKey(), r.operationType(), r.requestFingerprint(), resultRef, r.createdAt()));
        }
    }

    /** Fake gate điều khiển được — đếm số lần gọi để chốt hợp đồng "replay không đụng gate". */
    static class FakeKycGate implements KycGate {
        KycCheckResult next = new KycCheckResult(Decision.ALLOWED, "APPROVED");
        int calls = 0;
        Boolean calledInsideTransaction = null;
        @Override
        public KycCheckResult check(String userId) {
            calls++;
            calledInsideTransaction = org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive();
            return next;
        }
    }

    private final FakeKycGate gate = new FakeKycGate();
    private final InMemoryIdempotencyStore idemStore = new InMemoryIdempotencyStore();
    private final WalletService service = new WalletService(
            new InMemoryWalletRepository(), new InMemoryWithdrawalOrderRepository(),
            new TransactionTemplate(new NoopTransactionManager()), gate,
            new IdempotencyService(idemStore));

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
    void withdraw_createsPendingOrder_reservesAndAppendsHoldLedger() {
        Wallet w = service.createWallet("user-1", "Bob");
        service.topup(w.getId(), "user-1", new BigDecimal("100.00"), "k1");

        WithdrawalOrder order = service.withdraw(w.getId(), "user-1", new BigDecimal("30.00"), "k2");

        // (a) order PENDING, mang đúng payload + bankRef sinh ở bước ①
        assertEquals(WithdrawalState.PENDING, order.getState());
        assertEquals(0, new BigDecimal("30.00").compareTo(order.getAmount()));
        assertNotNull(order.getId());
        assertNotNull(order.getBankRef(), "bankRef sinh ở bước ① (E7)");

        // (b) escrow: available giảm, balance (total) CHƯA đổi
        Wallet after = service.getWallet(w.getId(), "user-1");
        assertEquals(0, new BigDecimal("100.00").compareTo(after.getBalance()), "balance/total chưa đổi ở bước ①");
        assertEquals(0, new BigDecimal("30.00").compareTo(after.getHeld()));
        assertEquals(0, new BigDecimal("70.00").compareTo(after.available()));

        // (c) ledger: 1 TOPUP + 1 WITHDRAW_HOLD (balanceAfter = total chưa đổi)
        List<WalletTransaction> txs = service.listTransactions(w.getId(), "user-1");
        assertEquals(2, txs.size());
        WalletTransaction hold = txs.stream()
                .filter(t -> t.type() == WalletTransaction.Type.WITHDRAW_HOLD).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("100.00").compareTo(hold.balanceAfter()));
    }

    @Test
    void withdraw_callsKycGateOutsideTransaction() {
        // D4: gọi mạng (KYC) KHÔNG được nằm trong transaction DB.
        Wallet w = service.createWallet("user-1", "Bob");
        service.topup(w.getId(), "user-1", new BigDecimal("100.00"), "k-seed");

        service.withdraw(w.getId(), "user-1", new BigDecimal("30.00"), "k-d4");

        assertEquals(Boolean.FALSE, gate.calledInsideTransaction,
                "cổng KYC phải được gọi NGOÀI transaction DB (D4)");
    }

    @Test
    void withdraw_sameIdempotencyKeyTwice_holdsOnce() {
        Wallet w = service.createWallet("user-1", "Bob");
        service.topup(w.getId(), "user-1", new BigDecimal("100.00"), "k-setup");
        WithdrawalOrder first = service.withdraw(w.getId(), "user-1", new BigDecimal("30.00"), "w-dup");

        WithdrawalOrder second = service.withdraw(w.getId(), "user-1", new BigDecimal("30.00"), "w-dup");

        assertEquals(first.getId(), second.getId(), "replay tra lai order CU, khong tao moi");
        Wallet after = service.getWallet(w.getId(), "user-1");
        assertEquals(0, new BigDecimal("30.00").compareTo(after.getHeld()), "held chi tang MOT lan");
        assertEquals(0, new BigDecimal("70.00").compareTo(after.available()));
        assertEquals(2, service.listTransactions(w.getId(), "user-1").size(), "1 topup + 1 WITHDRAW_HOLD");
    }

    @Test
    void sameIdempotencyKey_differentPayload_throwsConflictAndUnchanged() {
        Wallet w = service.createWallet("user-1", "Alice");
        service.topup(w.getId(), "user-1", new BigDecimal("50.00"), "key-mix");
        service.withdraw(w.getId(), "user-1", new BigDecimal("20.00"), "wkey-mix");

        // cùng withdraw key, amount khác -> conflict
        assertThrows(IdempotencyKeyConflictException.class,
                () -> service.withdraw(w.getId(), "user-1", new BigDecimal("25.00"), "wkey-mix"));
        // topup key khác type -> conflict ở tầng topup
        assertThrows(IdempotencyKeyConflictException.class,
                () -> service.topup(w.getId(), "user-1", new BigDecimal("60.00"), "key-mix"));

        Wallet after = service.getWallet(w.getId(), "user-1");
        assertEquals(0, new BigDecimal("50.00").compareTo(after.getBalance()), "balance KHONG doi khi key conflict");
        assertEquals(0, new BigDecimal("20.00").compareTo(after.getHeld()), "held KHONG doi khi key conflict");
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
    void withdraw_kycDenied_throws403Type_andNoOrderNoHold() {
        Wallet w = service.createWallet("user-1", "Alice");
        service.topup(w.getId(), "user-1", new BigDecimal("100"), "k1");
        gate.next = new KycGate.KycCheckResult(KycGate.Decision.DENIED, "PENDING");

        assertThrows(KycNotApprovedException.class,
                () -> service.withdraw(w.getId(), "user-1", new BigDecimal("10"), "k2"));
        Wallet after = service.getWallet(w.getId(), "user-1");
        assertEquals(0, BigDecimal.ZERO.compareTo(after.getHeld()), "KYC denied -> KHONG hold");
        assertEquals(1, service.listTransactions(w.getId(), "user-1").size(), "không có bút toán WITHDRAW_HOLD");
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

        WithdrawalOrder replay = service.withdraw(w.getId(), "user-1", new BigDecimal("10"), "kw");

        assertEquals(0, gate.calls, "replay KHÔNG gọi gate — trả order CŨ (đúng ngữ nghĩa idempotent)");
        assertEquals(0, new BigDecimal("10").compareTo(replay.getAmount()));
    }

    @Test
    void topup_neverCallsKycGate() {
        Wallet w = service.createWallet("user-1", "Alice");
        gate.calls = 0;
        service.topup(w.getId(), "user-1", new BigDecimal("5"), "kx");
        assertEquals(0, gate.calls, "R1: nạp tiền tự do, không gác");
    }

    @Test
    void withdraw_insufficientAvailable_throwsAndNoOrderNoHold() {
        Wallet w = service.createWallet("user-1", "Carol");

        assertThrows(InsufficientFundsException.class,
                () -> service.withdraw(w.getId(), "user-1", new BigDecimal("1.00"), "k3"));
        assertEquals(0, service.listTransactions(w.getId(), "user-1").size(), "thất bại -> KHÔNG có bút toán");
        assertEquals(0, BigDecimal.ZERO.compareTo(service.getWallet(w.getId(), "user-1").getHeld()));
    }

    @Test
    void withdraw_secondExceedsAvailableDueToHeld_throws() {
        // double-spend: rút 2 lần cùng available -> lần 2 thấy held -> InsufficientFunds (422)
        Wallet w = service.createWallet("user-1", "Dave");
        service.topup(w.getId(), "user-1", new BigDecimal("50.00"), "ds-fund");
        service.withdraw(w.getId(), "user-1", new BigDecimal("40.00"), "ds-1"); // available 10 con lai

        assertThrows(InsufficientFundsException.class,
                () -> service.withdraw(w.getId(), "user-1", new BigDecimal("20.00"), "ds-2"));
        Wallet after = service.getWallet(w.getId(), "user-1");
        assertEquals(0, new BigDecimal("40.00").compareTo(after.getHeld()), "held giu nguyen, lan 2 khong hold them");
    }

    @Test
    void getWithdrawalOrder_scopedToOwner() {
        Wallet w = service.createWallet("user-1", "Owner");
        service.topup(w.getId(), "user-1", new BigDecimal("100"), "gt");
        WithdrawalOrder order = service.withdraw(w.getId(), "user-1", new BigDecimal("10"), "gw");

        WithdrawalOrder found = service.getWithdrawalOrder(w.getId(), order.getId(), "user-1");
        assertEquals(order.getId(), found.getId());

        // chủ khác -> 404 (giấu tồn tại, D2)
        assertThrows(WalletNotFoundException.class,
                () -> service.getWithdrawalOrder(w.getId(), order.getId(), "user-EVIL"));
    }

    // ───────────────────────────── SP6 — transfer (TR1–TR7) ─────────────────────────────

    private Wallet seedWallet(String userId, String name, String balanceFundKey, BigDecimal balance) {
        Wallet w = service.createWallet(userId, name);
        if (balance.signum() > 0) {
            service.topup(w.getId(), userId, balance, balanceFundKey);
        }
        return w;
    }

    @Test
    void transfer_movesMoney_writesDoubleEntry_conservesTotal() {
        Wallet from = seedWallet("user-A", "Alice", "fA", new BigDecimal("100.00"));
        Wallet to = seedWallet("user-B", "Bob", "fB", new BigDecimal("20.00"));
        BigDecimal sumBefore = service.getWallet(from.getId(), "user-A").getBalance()
                .add(service.getWallet(to.getId(), "user-B").getBalance());

        WalletService.TransferResult result =
                service.transfer(from.getId(), to.getId(), "user-A", new BigDecimal("30.00"), "tk-1");

        Wallet fromAfter = service.getWallet(from.getId(), "user-A");
        Wallet toAfter = service.getWallet(to.getId(), "user-B");
        assertEquals(0, new BigDecimal("70.00").compareTo(fromAfter.getBalance()), "sender debited");
        assertEquals(0, new BigDecimal("50.00").compareTo(toAfter.getBalance()), "receiver credited");
        // tổng bảo toàn
        BigDecimal sumAfter = fromAfter.getBalance().add(toAfter.getBalance());
        assertEquals(0, sumBefore.compareTo(sumAfter), "Σ balance conserved");

        // double-entry: OUT(from,key) + IN(to,null), cùng transferId
        assertNotNull(result.transferId());
        WalletTransaction out = service.listTransactions(from.getId(), "user-A").stream()
                .filter(t -> t.type() == WalletTransaction.Type.TRANSFER_OUT).findFirst().orElseThrow();
        WalletTransaction in = service.listTransactions(to.getId(), "user-B").stream()
                .filter(t -> t.type() == WalletTransaction.Type.TRANSFER_IN).findFirst().orElseThrow();
        assertEquals("tk-1", out.idempotencyKey(), "key on OUT leg");
        assertNull(in.idempotencyKey(), "IN leg has NULL key");
        assertEquals(out.transferId(), in.transferId(), "shared transferId");
        assertEquals(result.transferId(), out.transferId());
    }

    // ───────────── SP7 Task 3 — dedup enforce QUA idempotency_record (dual-write) ─────────────

    @Test
    void topup_enforcesDedupViaIdempotencyRecord_andDualWritesLedger() {
        Wallet w = service.createWallet("user-1", "Alice");

        WalletTransaction tx = service.topup(w.getId(), "user-1", new BigDecimal("50.00"), "rec-topup");

        // (a) record được claim trong idempotency_record với result_ref = txId (switch-read source)
        IdempotencyRecord rec = idemStore.find("rec-topup").orElseThrow();
        assertEquals("TOPUP", rec.operationType());
        assertEquals(String.valueOf(tx.id()), rec.resultRef(), "result_ref trỏ txId của bút toán");
        // (b) dual-write: inline key vẫn ghi lên ledger (chưa bỏ UNIQUE — Task 5)
        assertEquals("rec-topup", tx.idempotencyKey(), "inline key dual-written lên ledger");
    }

    @Test
    void topup_replayEnforcedByRecord_appliesOnce() {
        Wallet w = service.createWallet("user-1", "Alice");
        WalletTransaction first = service.topup(w.getId(), "user-1", new BigDecimal("50.00"), "rec-dup");

        WalletTransaction second = service.topup(w.getId(), "user-1", new BigDecimal("50.00"), "rec-dup");

        assertEquals(first.id(), second.id(), "replay trả bút toán CŨ (enforce qua record)");
        assertEquals(0, new BigDecimal("50.00").compareTo(service.getWallet(w.getId(), "user-1").getBalance()),
                "tiền chỉ cộng MỘT lần");
        assertEquals(1, idemStore.rows.size(), "chỉ MỘT record cho key dùng lại");
    }

    @Test
    void topup_sameKeyDifferentAmount_throws409ViaRecordFingerprint() {
        Wallet w = service.createWallet("user-1", "Alice");
        service.topup(w.getId(), "user-1", new BigDecimal("50.00"), "rec-mix");

        assertThrows(IdempotencyKeyConflictException.class,
                () -> service.topup(w.getId(), "user-1", new BigDecimal("60.00"), "rec-mix"));
        assertEquals(0, new BigDecimal("50.00").compareTo(service.getWallet(w.getId(), "user-1").getBalance()),
                "payload lệch -> tiền KHÔNG đổi");
    }

    @Test
    void transfer_enforcesDedupViaIdempotencyRecord() {
        Wallet from = seedWallet("user-A", "Alice", "fA", new BigDecimal("100.00"));
        Wallet to = seedWallet("user-B", "Bob", "fB", new BigDecimal("0.00"));

        WalletService.TransferResult result =
                service.transfer(from.getId(), to.getId(), "user-A", new BigDecimal("30.00"), "rec-tk");

        IdempotencyRecord rec = idemStore.find("rec-tk").orElseThrow();
        assertEquals("TRANSFER_OUT", rec.operationType());
        assertEquals(result.transferId(), rec.resultRef(), "result_ref = transferId");
    }

    @Test
    void transfer_selfTransfer_throws400() {
        Wallet from = seedWallet("user-A", "Alice", "fA", new BigDecimal("100.00"));
        assertThrows(IllegalArgumentException.class,
                () -> service.transfer(from.getId(), from.getId(), "user-A", new BigDecimal("10.00"), "tk-self"));
    }

    @Test
    void transfer_insufficientFunds_rollsBack_noLedger() {
        Wallet from = seedWallet("user-A", "Alice", "fA", new BigDecimal("5.00"));
        Wallet to = seedWallet("user-B", "Bob", "fB", new BigDecimal("0.00"));

        assertThrows(InsufficientFundsException.class,
                () -> service.transfer(from.getId(), to.getId(), "user-A", new BigDecimal("10.00"), "tk-broke"));

        assertEquals(0, new BigDecimal("5.00").compareTo(service.getWallet(from.getId(), "user-A").getBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(service.getWallet(to.getId(), "user-B").getBalance()));
        assertEquals(0, service.listTransactions(from.getId(), "user-A").stream()
                .filter(t -> t.type() == WalletTransaction.Type.TRANSFER_OUT).count(), "no OUT ledger");
        assertEquals(0, service.listTransactions(to.getId(), "user-B").stream()
                .filter(t -> t.type() == WalletTransaction.Type.TRANSFER_IN).count(), "no IN ledger");
    }

    @Test
    void transfer_kycDenied_throws403_noMoneyMoved() {
        Wallet from = seedWallet("user-A", "Alice", "fA", new BigDecimal("100.00"));
        Wallet to = seedWallet("user-B", "Bob", "fB", new BigDecimal("0.00"));
        gate.next = new KycGate.KycCheckResult(KycGate.Decision.DENIED, "PENDING");

        assertThrows(KycNotApprovedException.class,
                () -> service.transfer(from.getId(), to.getId(), "user-A", new BigDecimal("10.00"), "tk-deny"));
        assertEquals(0, new BigDecimal("100.00").compareTo(service.getWallet(from.getId(), "user-A").getBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(service.getWallet(to.getId(), "user-B").getBalance()));
    }

    @Test
    void transfer_kycUnavailable_throws503() {
        Wallet from = seedWallet("user-A", "Alice", "fA", new BigDecimal("100.00"));
        Wallet to = seedWallet("user-B", "Bob", "fB", new BigDecimal("0.00"));
        gate.next = new KycGate.KycCheckResult(KycGate.Decision.UNAVAILABLE, null);

        assertThrows(KycUnavailableException.class,
                () -> service.transfer(from.getId(), to.getId(), "user-A", new BigDecimal("10.00"), "tk-503"));
    }

    @Test
    void transfer_checksKycOutsideTransaction() {
        Wallet from = seedWallet("user-A", "Alice", "fA", new BigDecimal("100.00"));
        Wallet to = seedWallet("user-B", "Bob", "fB", new BigDecimal("0.00"));

        service.transfer(from.getId(), to.getId(), "user-A", new BigDecimal("10.00"), "tk-d4");

        assertEquals(Boolean.FALSE, gate.calledInsideTransaction,
                "KYC gate must be called OUTSIDE the DB transaction (D4)");
    }

    @Test
    void transfer_replaySameKey_returnsExisting_neverMovesTwice() {
        Wallet from = seedWallet("user-A", "Alice", "fA", new BigDecimal("100.00"));
        Wallet to = seedWallet("user-B", "Bob", "fB", new BigDecimal("0.00"));
        WalletService.TransferResult first =
                service.transfer(from.getId(), to.getId(), "user-A", new BigDecimal("30.00"), "tk-replay");
        gate.calls = 0;
        gate.next = new KycGate.KycCheckResult(KycGate.Decision.DENIED, "REVOKED"); // would deny if checked

        WalletService.TransferResult replay =
                service.transfer(from.getId(), to.getId(), "user-A", new BigDecimal("30.00"), "tk-replay");

        assertEquals(first.transferId(), replay.transferId(), "replay returns the existing transfer");
        assertEquals(0, gate.calls, "replay does not re-check KYC gate");
        assertEquals(0, new BigDecimal("70.00").compareTo(service.getWallet(from.getId(), "user-A").getBalance()),
                "money moved only once");
        assertEquals(0, new BigDecimal("30.00").compareTo(service.getWallet(to.getId(), "user-B").getBalance()));
    }

    @Test
    void transfer_sameKeyDifferentPayload_throws409() {
        Wallet from = seedWallet("user-A", "Alice", "fA", new BigDecimal("100.00"));
        Wallet to = seedWallet("user-B", "Bob", "fB", new BigDecimal("0.00"));
        service.transfer(from.getId(), to.getId(), "user-A", new BigDecimal("30.00"), "tk-mix");

        assertThrows(IdempotencyKeyConflictException.class,
                () -> service.transfer(from.getId(), to.getId(), "user-A", new BigDecimal("40.00"), "tk-mix"));
    }

    /**
     * Receiver IS part of the idempotent payload (plan Task 3 §8 / design error-contract):
     * same key + same fromId + same amount but a DIFFERENT toWalletId must 409, not silently
     * replay the original transfer. The OUT leg does not store the receiver, so the paired
     * TRANSFER_IN (shared transferId) must be consulted to detect the mismatch.
     */
    @Test
    void transfer_sameKeyDifferentReceiver_throws409() {
        Wallet from = seedWallet("user-A", "Alice", "fA", new BigDecimal("100.00"));
        Wallet to = seedWallet("user-B", "Bob", "fB", new BigDecimal("0.00"));
        Wallet other = seedWallet("user-C", "Carol", "fC", new BigDecimal("0.00"));
        service.transfer(from.getId(), to.getId(), "user-A", new BigDecimal("30.00"), "tk-recv");

        assertThrows(IdempotencyKeyConflictException.class,
                () -> service.transfer(from.getId(), other.getId(), "user-A", new BigDecimal("30.00"), "tk-recv"));

        // and the original receiver is unchanged (no second credit leaked to the mismatched receiver)
        assertEquals(0, new BigDecimal("0.00").compareTo(service.getWallet(other.getId(), "user-C").getBalance()));
        assertEquals(0, new BigDecimal("30.00").compareTo(service.getWallet(to.getId(), "user-B").getBalance()));
    }

    @Test
    void transfer_senderNotOwnedByCaller_throws404() {
        Wallet from = seedWallet("user-A", "Alice", "fA", new BigDecimal("100.00"));
        Wallet to = seedWallet("user-B", "Bob", "fB", new BigDecimal("0.00"));

        // caller user-EVIL tries to transfer FROM user-A's wallet
        assertThrows(WalletNotFoundException.class,
                () -> service.transfer(from.getId(), to.getId(), "user-EVIL", new BigDecimal("10.00"), "tk-evil"));
    }

    @Test
    void transfer_receiverDoesNotExist_throws404() {
        Wallet from = seedWallet("user-A", "Alice", "fA", new BigDecimal("100.00"));

        assertThrows(WalletNotFoundException.class,
                () -> service.transfer(from.getId(), 99999L, "user-A", new BigDecimal("10.00"), "tk-norecv"));
    }

    @Test
    void transfer_blankKey_throws() {
        Wallet from = seedWallet("user-A", "Alice", "fA", new BigDecimal("100.00"));
        Wallet to = seedWallet("user-B", "Bob", "fB", new BigDecimal("0.00"));
        assertThrows(IllegalArgumentException.class,
                () -> service.transfer(from.getId(), to.getId(), "user-A", new BigDecimal("10.00"), " "));
    }
}
