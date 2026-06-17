package com.vng.wallet.infrastructure.web;

import com.vng.wallet.application.WalletService;
import com.vng.wallet.support.DefaultTenantHeaderConfig;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WalletTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WalletController.class)
@Import({GlobalExceptionHandler.class, WalletControllerTest.TestStubConfig.class, DefaultTenantHeaderConfig.class})
class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * Cung cấp WalletService thật, được "tiêm" một WalletRepository stub.
     * WalletRepository là PORT (interface) nên stub được bằng anonymous class —
     * không cần Mockito mock class cụ thể (tránh lỗi byte-buddy trên JDK mới).
     */
    @TestConfiguration
    static class TestStubConfig {
        /** No-op transaction manager — @WebMvcTest không có PlatformTransactionManager thật. */
        static TransactionTemplate noopTxTemplate() {
            return new TransactionTemplate(new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
                @Override protected void doCommit(DefaultTransactionStatus status) {}
                @Override protected void doRollback(DefaultTransactionStatus status) {}
            });
        }

        @Bean
        WalletService walletService() {
            return new WalletService(new WalletRepository() {
                @Override
                public Wallet save(Wallet wallet) {
                    // Stub: ví mới (id null) gán id=1; ví đã có (transfer save từ/đến) giữ NGUYÊN id +
                    // userId + ownerName + balance đã thay đổi (để TransferResponse phản ánh đúng).
                    Long id = wallet.getId() != null ? wallet.getId() : 1L;
                    return new Wallet(id, wallet.getUserId(), wallet.getOwnerName(),
                            wallet.getBalance(), wallet.getHeld(), 0L);
                }

                // Helper stub: ví id=1 (user-1, 250.00) là ví GỬI của caller user-1;
                // id=3 (user-3, 0.00) là ví NHẬN hợp lệ (khác chủ). id khác -> rỗng -> 404.
                @Override
                public Optional<Wallet> findById(Long id) {
                    if (id == 1L) {
                        return Optional.of(new Wallet(1L, "user-1", "Existing Owner", new BigDecimal("250.00"), BigDecimal.ZERO, 0L));
                    }
                    if (id == 3L) {
                        return Optional.of(new Wallet(3L, "user-3", "Receiver", new BigDecimal("0.00"), BigDecimal.ZERO, 0L));
                    }
                    // Stub: id=2 mô phỏng thua tranh chấp lock ở tầng hạ tầng -> 409.
                    if (id == 2L) {
                        throw new org.springframework.dao.CannotAcquireLockException("lock timeout");
                    }
                    return Optional.empty();
                }

                @Override
                public Optional<Wallet> findByIdAndUserId(Long id, String userId) {
                    return findById(id).filter(w -> userId != null && userId.equals(w.getUserId()));
                }

                @Override
                public List<Wallet> findAllByUserId(String userId) {
                    return findById(1L).filter(w -> userId != null && userId.equals(w.getUserId()))
                            .map(List::of).orElse(List.of());
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

                // In-memory ledger đơn giản cho stub.
                private final List<WalletTransaction> transactions = new ArrayList<>();
                private final Map<String, WalletTransaction> byKey = new HashMap<>();
                private final AtomicLong txSeq = new AtomicLong(0);

                @Override
                public WalletTransaction saveTransaction(WalletTransaction transaction) {
                    WalletTransaction saved = new WalletTransaction(
                            transaction.id() != null ? transaction.id() : txSeq.incrementAndGet(),
                            transaction.walletId(), transaction.type(), transaction.amount(),
                            transaction.idempotencyKey(), transaction.balanceAfter(), transaction.createdAt(),
                            transaction.transferId());
                    transactions.add(saved);
                    if (saved.idempotencyKey() != null) {       // chân TRANSFER_IN có key=null — không index
                        byKey.put(saved.idempotencyKey(), saved);
                    }
                    return saved;
                }

                @Override
                public Optional<WalletTransaction> findTransactionByIdempotencyKey(String idempotencyKey) {
                    return Optional.ofNullable(byKey.get(idempotencyKey));
                }

                @Override
                public Optional<WalletTransaction> findTransactionByTransferIdAndType(
                        String transferId, WalletTransaction.Type type) {
                    return Optional.empty();
                }

                @Override
                public List<WalletTransaction> listTransactions(Long walletId) {
                    return transactions.stream().filter(t -> t.walletId().equals(walletId)).toList();
                }
            }, inMemoryOrderRepo(), noopTxTemplate(),
            // Gate stub: mặc định ALLOWED; caller "kyc-denied" -> DENIED (để test transfer 403).
            userId -> "kyc-denied".equals(userId)
                    ? new com.vng.wallet.domain.KycGate.KycCheckResult(
                            com.vng.wallet.domain.KycGate.Decision.DENIED, "PENDING")
                    : new com.vng.wallet.domain.KycGate.KycCheckResult(
                            com.vng.wallet.domain.KycGate.Decision.ALLOWED, "APPROVED"),
            new com.vng.wallet.idempotency.IdempotencyService(inMemoryIdempotencyStore()));
        }

        /** Stub idempotency store — INSERT thuần (trùng key → DIVE) mô phỏng UNIQUE PK (SP7 Task 3). */
        static com.vng.wallet.idempotency.IdempotencyStore inMemoryIdempotencyStore() {
            return new com.vng.wallet.idempotency.IdempotencyStore() {
                private final Map<String, com.vng.wallet.idempotency.IdempotencyRecord> rows = new HashMap<>();

                @Override
                public Optional<com.vng.wallet.idempotency.IdempotencyRecord> find(String idempotencyKey) {
                    return Optional.ofNullable(rows.get(idempotencyKey));
                }

                @Override
                public com.vng.wallet.idempotency.IdempotencyRecord save(
                        com.vng.wallet.idempotency.IdempotencyRecord record) {
                    if (rows.containsKey(record.idempotencyKey())) {
                        throw new org.springframework.dao.DataIntegrityViolationException(
                                "duplicate key " + record.idempotencyKey());
                    }
                    rows.put(record.idempotencyKey(), record);
                    return record;
                }

                @Override
                public void updateResultRef(String idempotencyKey, String resultRef) {
                    var r = rows.get(idempotencyKey);
                    if (r == null) return;
                    rows.put(idempotencyKey, new com.vng.wallet.idempotency.IdempotencyRecord(
                            r.idempotencyKey(), r.operationType(), r.requestFingerprint(), resultRef, r.createdAt()));
                }

                @Override
                public int deleteOlderThan(java.time.Instant cutoff) {
                    int before = rows.size();
                    rows.values().removeIf(r -> r.createdAt().isBefore(cutoff));
                    return before - rows.size();
                }
            };
        }

        /** Stub order repo — đủ cho test web (replay rỗng + lưu mới gán id). */
        static com.vng.wallet.domain.WithdrawalOrderRepository inMemoryOrderRepo() {
            return new com.vng.wallet.domain.WithdrawalOrderRepository() {
                private final Map<Long, com.vng.wallet.domain.WithdrawalOrder> store = new HashMap<>();
                private final Map<String, com.vng.wallet.domain.WithdrawalOrder> byKey = new HashMap<>();
                private final AtomicLong seq = new AtomicLong(0);

                @Override
                public com.vng.wallet.domain.WithdrawalOrder save(com.vng.wallet.domain.WithdrawalOrder o) {
                    Long id = o.getId() != null ? o.getId() : seq.incrementAndGet();
                    var saved = new com.vng.wallet.domain.WithdrawalOrder(id, o.getUserId(), o.getWalletId(),
                            o.getAmount(), o.getState(), o.getBankRef(), o.getIdempotencyKey(),
                            o.getAttemptCount(), o.getFirstSentAt(), o.getVersion());
                    store.put(id, saved);
                    byKey.put(saved.getIdempotencyKey(), saved);
                    return saved;
                }

                @Override
                public Optional<com.vng.wallet.domain.WithdrawalOrder> findById(Long id) {
                    return Optional.ofNullable(store.get(id));
                }

                @Override
                public Optional<com.vng.wallet.domain.WithdrawalOrder> findByIdempotencyKey(String key) {
                    return Optional.ofNullable(byKey.get(key));
                }

                @Override
                public Optional<com.vng.wallet.domain.WithdrawalOrder> findByBankRef(String bankRef) {
                    return store.values().stream().filter(o -> bankRef.equals(o.getBankRef())).findFirst();
                }

                @Override
                public Optional<com.vng.wallet.domain.WithdrawalOrder> findByIdAndUserId(Long id, String userId) {
                    return Optional.ofNullable(store.get(id))
                            .filter(o -> userId != null && userId.equals(o.getUserId()));
                }

                @Override
                public List<com.vng.wallet.domain.WithdrawalOrder> findReconcilable(int limit) {
                    return List.of();
                }
            };
        }
    }

    @Test
    void createWallet_returns201WithZeroBalance() throws Exception {
        mockMvc.perform(post("/wallets")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"Alice\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.ownerName").value("Alice"))
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void getExistingWallet_returns200WithBody() throws Exception {
        mockMvc.perform(get("/wallets/1").header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.ownerName").value("Existing Owner"))
                .andExpect(jsonPath("$.balance").value(250.00));
    }

    @Test
    void getMissingWallet_returns404() throws Exception {
        mockMvc.perform(get("/wallets/999999").header("X-User-Id", "user-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Wallet not found with id: 999999"));
    }

    @Test
    void withdraw_lockConflict_returns409() throws Exception {
        // Stub repository ném CannotAcquireLockException cho id=2 -> handler map ConcurrencyFailureException -> 409.
        mockMvc.perform(post("/wallets/2/withdraw")
                        .header("X-User-Id", "user-1")
                        .header("Idempotency-Key", "lock-409")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":5.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Concurrent update, please retry"));
    }

    @Test
    void createWallet_emptyOwner_returns400() throws Exception {
        mockMvc.perform(post("/wallets")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ownerName must not be empty"));
    }

    @Test
    void createWallet_nullOwner_returns400() throws Exception {
        mockMvc.perform(post("/wallets")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ownerName must not be empty"));
    }

    @Test
    void createWallet_whitespaceOwner_returns400() throws Exception {
        mockMvc.perform(post("/wallets")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ownerName must not be empty"));
    }

    // ──────────────────────────── SP6 Task 4 — POST /wallets/{id}/transfer ────────────────────────────

    @Test
    void transfer_returns200WithTransferResponse() throws Exception {
        mockMvc.perform(post("/wallets/1/transfer")
                        .header("X-User-Id", "user-1")
                        .header("Idempotency-Key", "tk-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toWalletId\":3,\"amount\":30.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").isNotEmpty())
                .andExpect(jsonPath("$.from").value(1))
                .andExpect(jsonPath("$.to").value(3))
                .andExpect(jsonPath("$.amount").value(30.00));
    }

    @Test
    void transfer_selfTransfer_returns400() throws Exception {
        mockMvc.perform(post("/wallets/1/transfer")
                        .header("X-User-Id", "user-1")
                        .header("Idempotency-Key", "tk-self")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toWalletId\":1,\"amount\":10.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("cannot transfer to the same wallet"));
    }

    @Test
    void transfer_senderNotOwnedByCaller_returns404() throws Exception {
        // caller user-EVIL không sở hữu ví id=1 -> sender scoped lookup rỗng -> 404 (D2/D3).
        mockMvc.perform(post("/wallets/1/transfer")
                        .header("X-User-Id", "user-EVIL")
                        .header("Idempotency-Key", "tk-evil")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toWalletId\":3,\"amount\":10.00}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void transfer_kycDenied_returns403() throws Exception {
        // caller "kyc-denied" sở hữu ví id=1? Không — nhưng KYC gác NGOÀI tx, TRƯỚC khi load ví,
        // nên DENIED chặn ngay với 403 bất kể chủ ví.
        mockMvc.perform(post("/wallets/1/transfer")
                        .header("X-User-Id", "kyc-denied")
                        .header("Idempotency-Key", "tk-kyc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toWalletId\":3,\"amount\":10.00}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void transfer_insufficientFunds_returns422() throws Exception {
        // ví id=1 có 250.00; chuyển 999.00 -> InsufficientFunds -> 422.
        mockMvc.perform(post("/wallets/1/transfer")
                        .header("X-User-Id", "user-1")
                        .header("Idempotency-Key", "tk-poor")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toWalletId\":3,\"amount\":999.00}"))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void transfer_sameKeyDifferentPayload_returns409() throws Exception {
        // Lần 1: key "tk-dup" -> 200 (lưu chân OUT với amount=30 cho key này).
        mockMvc.perform(post("/wallets/1/transfer")
                        .header("X-User-Id", "user-1")
                        .header("Idempotency-Key", "tk-dup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toWalletId\":3,\"amount\":30.00}"))
                .andExpect(status().isOk());

        // Lần 2: cùng key, amount khác -> IdempotencyKeyConflict trên đường transfer -> 409
        // (plan Task 4 Step 3 / design error-contract: "Cùng key, khác payload -> 409").
        mockMvc.perform(post("/wallets/1/transfer")
                        .header("X-User-Id", "user-1")
                        .header("Idempotency-Key", "tk-dup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toWalletId\":3,\"amount\":60.00}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void transfer_missingIdempotencyKey_returns400() throws Exception {
        mockMvc.perform(post("/wallets/1/transfer")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toWalletId\":3,\"amount\":10.00}"))
                .andExpect(status().isBadRequest());
    }
}
