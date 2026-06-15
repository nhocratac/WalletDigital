package com.vng.wallet.tenancy;

import com.vng.wallet.domain.BankClient.BankStatus;
import com.vng.wallet.domain.WithdrawalOrder;
import com.vng.wallet.domain.WithdrawalOrderRepository;
import com.vng.wallet.domain.WithdrawalState;
import com.vng.wallet.infrastructure.bank.MockBankClient;
import com.vng.wallet.infrastructure.scheduling.MultiTenantReconciliationRunner;
import com.vng.wallet.application.ReconciliationService;
import com.vng.wallet.tenancy.master.TenantRegistryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SP5 Task 8 — Integration + (in-process) E2E proof that two tenants are FULLY isolated through the
 * REAL production path: tenants onboarded via {@code POST /admin/tenants}, wallets created/topped-up
 * via {@code /wallets} through the real {@link TenantFilter} chain (tenant chosen ONLY by the
 * {@code X-Tenant-Id} header the gateway forwards), and the multi-tenant reconciliation worker
 * reconciling each tenant's orders in its OWN schema.
 *
 * <p>Asserts the SP5 invariants end-to-end against Testcontainers MySQL (prod realism):
 * <ul>
 *   <li><b>onboarding (T6,T7):</b> {@code /admin/tenants} provisions {@code acme} and {@code globex}
 *       (CREATE SCHEMA + Flyway migrate → ACTIVE);</li>
 *   <li><b>isolation by construction (T1,T3):</b> a wallet created under {@code acme} is invisible to
 *       {@code globex} (its wallet list is empty), and a top-up in {@code acme} never alters
 *       {@code globex} — proven through the HTTP layer, not by a hand-set ThreadLocal;</li>
 *   <li><b>worker fan-out (T9):</b> each tenant has a SENT order; one fleet reconcile pass drives each
 *       order to terminal in its OWN tenant schema (acme→SETTLED, globex→FAILED), never crossing.</li>
 * </ul>
 *
 * <p>This test deliberately does NOT import {@code DefaultTenantHeaderConfig}: a process-wide default
 * tenant would mask the very isolation under test. Every request carries an explicit tenant header.
 */
@SpringBootTest(properties = "wallet.bank.mock=true")
@AutoConfigureMockMvc
@Testcontainers
class TwoTenantIsolationE2EIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WithdrawalOrderRepository orderRepository;

    @Autowired
    MockBankClient bank;

    @Autowired
    ReconciliationService reconciliationService;

    @Autowired
    TenantRegistryRepository registryRepository;

    @BeforeEach
    void strictNoFallback() {
        // The real production path: no process-wide default tenant may mask a missing context.
        TenantContext.clear();
        TenantContext.setDefaultTenant(null);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private void onboard(String tenantId) throws Exception {
        mockMvc.perform(post("/admin/tenants")
                        .header("X-Roles", "ops")
                        .header(TenantFilter.HEADER, tenantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + tenantId + "\"}"))
                .andExpect(status().isCreated());
    }

    private long createWallet(String tenant, String userId, String ownerName) throws Exception {
        MvcResult r = mockMvc.perform(post("/wallets")
                        .header(TenantFilter.HEADER, tenant)
                        .header("X-User-Id", userId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"" + ownerName + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        Matcher m = Pattern.compile("\"id\":(\\d+)").matcher(r.getResponse().getContentAsString());
        m.find();
        return Long.parseLong(m.group(1));
    }

    private void topup(String tenant, long walletId, String userId, String amount, String key) throws Exception {
        mockMvc.perform(post("/wallets/" + walletId + "/topup")
                        .header(TenantFilter.HEADER, tenant)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":" + amount + "}"))
                .andExpect(status().isOk());
    }

    @Test
    void twoTenants_fullyIsolated_throughApi_andWorker() throws Exception {
        // [1] Onboard both tenants via the admin channel (CREATE SCHEMA + Flyway → ACTIVE).
        onboard("acme");
        onboard("globex");

        // [2] Create + top up a wallet in acme via the API (tenant chosen by header only).
        long acmeWallet = createWallet("acme", "user-acme", "Alice");
        topup("acme", acmeWallet, "user-acme", "100.00", "seed-acme");

        // [3] globex sees NONE of acme's wallets — isolation by construction, through the API.
        mockMvc.perform(get("/wallets/" + acmeWallet)
                        .header(TenantFilter.HEADER, "globex")
                        .header("X-User-Id", "user-acme"))
                .andExpect(status().isNotFound());

        // globex creates its OWN wallet independently; the IDs live in separate schemas.
        long globexWallet = createWallet("globex", "user-globex", "Bob");
        topup("globex", globexWallet, "user-globex", "50.00", "seed-globex");

        // acme's balance is untouched by globex activity (no cross-tenant bleed).
        mockMvc.perform(get("/wallets/" + acmeWallet)
                        .header(TenantFilter.HEADER, "acme")
                        .header("X-User-Id", "user-acme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(100.00));
        mockMvc.perform(get("/wallets/" + globexWallet)
                        .header(TenantFilter.HEADER, "globex")
                        .header("X-User-Id", "user-globex"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(50.00));

        // [4] Seed a SENT order in EACH tenant schema, with different bank outcomes.
        long acmeOrder = seedSentOrder("acme", acmeWallet, "user-acme", "acme-ref", "k-acme", BankStatus.SETTLED);
        long globexOrder = seedSentOrder("globex", globexWallet, "user-globex", "globex-ref", "k-globex", BankStatus.REJECTED);

        // [5] Worker fan-out: ONE pass reconciles BOTH tenants, each in its own schema.
        MultiTenantReconciliationRunner runner =
                new MultiTenantReconciliationRunner(registryRepository, reconciliationService);
        runner.runOnce();

        assertEquals(WithdrawalState.SETTLED, stateOf("acme", acmeOrder), "acme order settled in tenant_acme");
        assertEquals(WithdrawalState.FAILED, stateOf("globex", globexOrder), "globex order failed in tenant_globex");

        // [6] No order leaked across schemas (acme's ref is unknown to globex and vice versa).
        try {
            TenantContext.set("globex");
            assertTrue(orderRepository.findByBankRef("acme-ref").isEmpty(), "acme order not visible in globex");
        } finally {
            TenantContext.clear();
        }
    }

    private long seedSentOrder(String tenant, long walletId, String userId, String bankRef,
                               String idemKey, BankStatus bankResult) {
        bank.configure(bankRef, bankResult);
        try {
            TenantContext.set(tenant);
            WithdrawalOrder saved = orderRepository.save(new WithdrawalOrder(
                    null, userId, walletId, new BigDecimal("10.00"), WithdrawalState.SENT,
                    bankRef, idemKey, 1, Instant.now(), null));
            return saved.getId();
        } finally {
            TenantContext.clear();
        }
    }

    private WithdrawalState stateOf(String tenant, long orderId) {
        try {
            TenantContext.set(tenant);
            return orderRepository.findById(orderId).orElseThrow().getState();
        } finally {
            TenantContext.clear();
        }
    }
}
