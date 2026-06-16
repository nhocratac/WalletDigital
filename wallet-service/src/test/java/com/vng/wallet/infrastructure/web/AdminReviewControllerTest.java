package com.vng.wallet.infrastructure.web;

import com.vng.wallet.application.WithdrawalSettlementService;
import com.vng.wallet.support.DefaultTenantHeaderConfig;
import com.vng.wallet.domain.BankClient;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WalletTransaction;
import com.vng.wallet.domain.WithdrawalOrder;
import com.vng.wallet.domain.WithdrawalOrderRepository;
import com.vng.wallet.domain.WithdrawalState;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Task 6 (E10): admin resolve cho order NEEDS_MANUAL_REVIEW.
 * POST /admin/withdrawals/{orderId}/resolve {decision: SETTLED|FAILED} -> qua cửa nguyên tử applyTerminal.
 * AuthZ: header X-Roles phải chứa ops/compliance, ngược lại 403.
 */
@WebMvcTest(AdminReviewController.class)
@Import({GlobalExceptionHandler.class, AdminReviewControllerTest.TestStubConfig.class, DefaultTenantHeaderConfig.class})
class AdminReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    static final InMemoryWalletRepository wallets = new InMemoryWalletRepository();
    static final InMemoryOrderRepository orders = new InMemoryOrderRepository();

    @TestConfiguration
    static class TestStubConfig {
        static TransactionTemplate noopTx() {
            return new TransactionTemplate(new AbstractPlatformTransactionManager() {
                @Override protected Object doGetTransaction() { return new Object(); }
                @Override protected void doBegin(Object t, TransactionDefinition d) {}
                @Override protected void doCommit(DefaultTransactionStatus s) {}
                @Override protected void doRollback(DefaultTransactionStatus s) {}
            });
        }

        @Bean
        WithdrawalSettlementService settlementService() {
            BankClient bank = new BankClient() {
                @Override public TransferAck transfer(String bankRef, BigDecimal amount) { return new TransferAck(BankStatus.UNKNOWN); }
                @Override public BankStatus status(String bankRef) { return BankStatus.UNKNOWN; }
            };
            return new WithdrawalSettlementService(orders, wallets, bank, noopTx());
        }
    }

    private Long seedReviewOrder() {
        wallets.store.clear();
        orders.store.clear();
        wallets.transactions.clear();
        Wallet w = wallets.save(new Wallet(null, "user-1", "Alice",
                new BigDecimal("100"), new BigDecimal("30"), null));
        WithdrawalOrder o = orders.save(new WithdrawalOrder(null, "user-1", w.getId(), new BigDecimal("30"),
                WithdrawalState.NEEDS_MANUAL_REVIEW, "wd-ref-1", "k1",
                WithdrawalOrder.MAX_ATTEMPTS, Instant.now(), null));
        return o.getId();
    }

    @Test
    void resolveSettled_withOpsRole_appliesSettleAndReturns200() throws Exception {
        Long orderId = seedReviewOrder();
        mockMvc.perform(post("/admin/withdrawals/{orderId}/resolve", orderId)
                        .header("X-Roles", "ops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"SETTLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SETTLED"));

        WithdrawalOrder after = orders.findById(orderId).orElseThrow();
        assertEquals(WithdrawalState.SETTLED, after.getState());
        Wallet w = wallets.store.values().iterator().next();
        assertEquals(0, new BigDecimal("70").compareTo(w.getBalance()), "settle -> balance giảm");
        assertEquals(0, BigDecimal.ZERO.compareTo(w.getHeld()));
    }

    @Test
    void resolveFailed_withComplianceRole_refundsAndReturns200() throws Exception {
        Long orderId = seedReviewOrder();
        mockMvc.perform(post("/admin/withdrawals/{orderId}/resolve", orderId)
                        .header("X-Roles", "compliance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"FAILED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FAILED"));

        WithdrawalOrder after = orders.findById(orderId).orElseThrow();
        assertEquals(WithdrawalState.FAILED, after.getState());
        Wallet w = wallets.store.values().iterator().next();
        assertEquals(0, new BigDecimal("100").compareTo(w.getBalance()), "refund -> balance không đổi");
        assertEquals(0, BigDecimal.ZERO.compareTo(w.getHeld()), "refund -> held về 0");
    }

    @Test
    void resolve_withoutAuthorizedRole_returns403() throws Exception {
        Long orderId = seedReviewOrder();
        mockMvc.perform(post("/admin/withdrawals/{orderId}/resolve", orderId)
                        .header("X-Roles", "user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"SETTLED\"}"))
                .andExpect(status().isForbidden());

        assertEquals(WithdrawalState.NEEDS_MANUAL_REVIEW,
                orders.findById(orderId).orElseThrow().getState(), "không role -> không đổi state");
    }

    @Test
    void resolve_missingRolesHeader_returns403() throws Exception {
        Long orderId = seedReviewOrder();
        mockMvc.perform(post("/admin/withdrawals/{orderId}/resolve", orderId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"FAILED\"}"))
                .andExpect(status().isForbidden());
    }

    // --- In-memory repos (optimistic-lock semantics như các test khác) ---

    static class InMemoryWalletRepository implements WalletRepository {
        final Map<Long, Wallet> store = new HashMap<>();
        final List<WalletTransaction> transactions = new ArrayList<>();
        final AtomicLong seq = new AtomicLong(0);
        final AtomicLong txSeq = new AtomicLong(0);

        @Override public Wallet save(Wallet wallet) {
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

        @Override public WithdrawalOrder save(WithdrawalOrder order) {
            Long id = order.getId() != null ? order.getId() : seq.incrementAndGet();
            long v = (order.getVersion() == null ? 0L : order.getVersion()) + 1;
            WithdrawalOrder saved = new WithdrawalOrder(id, order.getUserId(), order.getWalletId(),
                    order.getAmount(), order.getState(), order.getBankRef(), order.getIdempotencyKey(),
                    order.getAttemptCount(), order.getFirstSentAt(), v);
            store.put(id, saved);
            return saved;
        }
        @Override public Optional<WithdrawalOrder> findById(Long id) { return Optional.ofNullable(store.get(id)); }
        @Override public Optional<WithdrawalOrder> findByIdempotencyKey(String k) { return Optional.empty(); }
        @Override public Optional<WithdrawalOrder> findByBankRef(String r) { return Optional.empty(); }
        @Override public Optional<WithdrawalOrder> findByIdAndUserId(Long id, String userId) {
            return findById(id).filter(o -> userId != null && userId.equals(o.getUserId()));
        }
        @Override public List<WithdrawalOrder> findReconcilable(int limit) { return List.of(); }
    }
}
