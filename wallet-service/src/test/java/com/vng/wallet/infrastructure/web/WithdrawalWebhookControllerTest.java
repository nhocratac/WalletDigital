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
import com.vng.wallet.infrastructure.kyc.HmacSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 7 (E5 fast path): bank settlement webhook.
 * POST /webhooks/bank/settlement {bankRef, result} ký HMAC bank (secret RIÊNG) -> applyTerminal (cửa chung).
 * 200 cho mọi case hợp lệ (APPLIED / DUPLICATE / IGNORED — không 4xx, tránh bank retry vô hạn);
 * sai chữ ký -> 401.
 */
@WebMvcTest(WithdrawalWebhookController.class)
@Import({GlobalExceptionHandler.class, WithdrawalWebhookControllerTest.TestStubConfig.class, DefaultTenantHeaderConfig.class})
@TestPropertySource(properties = "wallet.bank.webhook-secret=" + WithdrawalWebhookControllerTest.SECRET)
class WithdrawalWebhookControllerTest {

    static final String SECRET = "bank-webhook-secret-test";
    static final String PATH = "/webhooks/bank/settlement";
    private static final HmacSigner SIGNER = new HmacSigner();

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

        @Bean
        HmacSigner hmacSigner() { return new HmacSigner(); }

        @Bean
        WithdrawalOrderRepository orderRepository() { return orders; }
    }

    private void seedSentOrder() {
        wallets.store.clear();
        orders.store.clear();
        wallets.transactions.clear();
        Wallet w = wallets.save(new Wallet(null, "user-1", "Alice",
                new BigDecimal("100"), new BigDecimal("30"), null));
        orders.save(new WithdrawalOrder(null, "user-1", w.getId(), new BigDecimal("30"),
                WithdrawalState.SENT, "wd-ref-1", "k1", 1, Instant.now(), null));
    }

    private String sign(String body) {
        String ts = Long.toString(Instant.now().getEpochSecond());
        return ts + ":" + SIGNER.sign(SECRET, "bank", "POST", PATH, ts,
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validSettled_appliesTerminal_andReturns200Applied() throws Exception {
        seedSentOrder();
        String body = "{\"bankRef\":\"wd-ref-1\",\"result\":\"SETTLED\"}";
        String tsSig = sign(body);
        mockMvc.perform(post(PATH)
                        .header("X-Timestamp", tsSig.split(":", 2)[0])
                        .header("X-Signature", tsSig.split(":", 2)[1])
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED"));

        WithdrawalOrder after = orders.findByBankRef("wd-ref-1").orElseThrow();
        assertEquals(WithdrawalState.SETTLED, after.getState());
        Wallet w = wallets.store.values().iterator().next();
        assertEquals(0, new BigDecimal("70").compareTo(w.getBalance()), "settle -> balance giảm");
        assertEquals(0, BigDecimal.ZERO.compareTo(w.getHeld()));
    }

    @Test
    void validRejected_refunds_andReturns200() throws Exception {
        seedSentOrder();
        String body = "{\"bankRef\":\"wd-ref-1\",\"result\":\"REJECTED\"}";
        String tsSig = sign(body);
        mockMvc.perform(post(PATH)
                        .header("X-Timestamp", tsSig.split(":", 2)[0])
                        .header("X-Signature", tsSig.split(":", 2)[1])
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED"));

        WithdrawalOrder after = orders.findByBankRef("wd-ref-1").orElseThrow();
        assertEquals(WithdrawalState.FAILED, after.getState());
        Wallet w = wallets.store.values().iterator().next();
        assertEquals(0, new BigDecimal("100").compareTo(w.getBalance()), "refund -> balance không đổi");
        assertEquals(0, BigDecimal.ZERO.compareTo(w.getHeld()), "refund -> held về 0");
    }

    @Test
    void terminalOrder_returns200Duplicate_noStateChange() throws Exception {
        wallets.store.clear();
        orders.store.clear();
        wallets.transactions.clear();
        Wallet w = wallets.save(new Wallet(null, "user-1", "Alice",
                new BigDecimal("70"), BigDecimal.ZERO, null));
        orders.save(new WithdrawalOrder(null, "user-1", w.getId(), new BigDecimal("30"),
                WithdrawalState.SETTLED, "wd-ref-1", "k1", 1, Instant.now(), null));

        String body = "{\"bankRef\":\"wd-ref-1\",\"result\":\"SETTLED\"}";
        String tsSig = sign(body);
        mockMvc.perform(post(PATH)
                        .header("X-Timestamp", tsSig.split(":", 2)[0])
                        .header("X-Signature", tsSig.split(":", 2)[1])
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DUPLICATE"));

        assertEquals(0, new BigDecimal("70").compareTo(
                wallets.store.values().iterator().next().getBalance()), "no-op, không trừ kép");
    }

    @Test
    void unknownBankRef_returns200Ignored() throws Exception {
        seedSentOrder();
        String body = "{\"bankRef\":\"does-not-exist\",\"result\":\"SETTLED\"}";
        String tsSig = sign(body);
        mockMvc.perform(post(PATH)
                        .header("X-Timestamp", tsSig.split(":", 2)[0])
                        .header("X-Signature", tsSig.split(":", 2)[1])
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("IGNORED"));

        assertEquals(WithdrawalState.SENT,
                orders.findByBankRef("wd-ref-1").orElseThrow().getState(), "order khác không bị đụng");
    }

    @Test
    void badSignature_returns401() throws Exception {
        seedSentOrder();
        String body = "{\"bankRef\":\"wd-ref-1\",\"result\":\"SETTLED\"}";
        mockMvc.perform(post(PATH)
                        .header("X-Timestamp", Long.toString(Instant.now().getEpochSecond()))
                        .header("X-Signature", "deadbeef")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isUnauthorized());

        assertEquals(WithdrawalState.SENT,
                orders.findByBankRef("wd-ref-1").orElseThrow().getState(), "sai chữ ký -> không đổi state");
    }

    // --- In-memory repos ---

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
        @Override public Optional<WithdrawalOrder> findByBankRef(String r) {
            return store.values().stream().filter(o -> r.equals(o.getBankRef())).findFirst();
        }
        @Override public Optional<WithdrawalOrder> findByIdAndUserId(Long id, String userId) {
            return findById(id).filter(o -> userId != null && userId.equals(o.getUserId()));
        }
        @Override public List<WithdrawalOrder> findReconcilable(int limit) { return List.of(); }
    }
}
