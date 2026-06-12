package com.vng.wallet.infrastructure.web;

import com.vng.wallet.application.WalletService;
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
@Import({GlobalExceptionHandler.class, WalletControllerTest.TestStubConfig.class})
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
                    // Stub: gán id cố định, giữ nguyên userId + ownerName + balance (0 cho ví mới).
                    return new Wallet(1L, wallet.getUserId(), wallet.getOwnerName(), wallet.getBalance(), 0L);
                }

                @Override
                public Optional<Wallet> findById(Long id) {
                    // Stub: chỉ ví id=1 tồn tại (số dư 250.00); id khác -> rỗng -> 404.
                    if (id == 1L) {
                        return Optional.of(new Wallet(1L, "user-1", "Existing Owner", new BigDecimal("250.00"), 0L));
                    }
                    // Stub: id=2 mô phỏng thua tranh chấp lock ở tầng hạ tầng -> 409.
                    if (id == 2L) {
                        throw new org.springframework.dao.CannotAcquireLockException("lock timeout");
                    }
                    return Optional.empty();
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
                            transaction.idempotencyKey(), transaction.balanceAfter(), transaction.createdAt());
                    transactions.add(saved);
                    byKey.put(saved.idempotencyKey(), saved);
                    return saved;
                }

                @Override
                public Optional<WalletTransaction> findTransactionByIdempotencyKey(String idempotencyKey) {
                    return Optional.ofNullable(byKey.get(idempotencyKey));
                }

                @Override
                public List<WalletTransaction> listTransactions(Long walletId) {
                    return transactions.stream().filter(t -> t.walletId().equals(walletId)).toList();
                }
            }, noopTxTemplate());
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
        mockMvc.perform(get("/wallets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.ownerName").value("Existing Owner"))
                .andExpect(jsonPath("$.balance").value(250.00));
    }

    @Test
    void getMissingWallet_returns404() throws Exception {
        mockMvc.perform(get("/wallets/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Wallet not found with id: 999999"));
    }

    @Test
    void withdraw_lockConflict_returns409() throws Exception {
        // Stub repository ném CannotAcquireLockException cho id=2 -> handler map ConcurrencyFailureException -> 409.
        mockMvc.perform(post("/wallets/2/withdraw")
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
}
