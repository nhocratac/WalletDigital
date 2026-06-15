package com.vng.wallet.tenancy;

import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WithdrawalOrder;
import com.vng.wallet.domain.WithdrawalOrderRepository;
import com.vng.wallet.domain.WithdrawalState;
import com.vng.wallet.infrastructure.kyc.HmacSigner;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SP5 Task 7 review fix — FULL-STACK proof that the bank settlement webhook works in the PRODUCTION
 * request path: through the real {@link TenantFilter} chain, with NO {@code X-Tenant-Id} header (the
 * bank never goes through the gateway, so it sends none).
 *
 * <p>This is the test the prior {@code @WebMvcTest} slice could not be: that slice did not register the
 * plain-{@code @Component} filter and injected a default tenant header, so it could not catch the
 * blocking bug where {@link TenantFilter} 400s the header-less callback before the controller runs.
 * Here the real filter runs and MUST exempt {@code /webhooks/**}; the controller then recovers the
 * tenant from the {@code bankRef} and routes the settlement to the correct tenant schema.
 *
 * <p>Cases:
 * <ul>
 *   <li>tenant-encoded ref → settlement applied in that tenant's schema (and NOT the other tenant);</li>
 *   <li>fail-closed gap: legacy/foreign ref on the header-less webhook → IGNORED (200), never a 500.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "wallet.bank.webhook-secret=" + WebhookTenantResolutionIntegrationTest.SECRET)
class WebhookTenantResolutionIntegrationTest {

    static final String SECRET = "bank-webhook-secret-it";
    static final String PATH = "/webhooks/bank/settlement";
    private static final HmacSigner SIGNER = new HmacSigner();

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
    WalletRepository walletRepository;

    @Autowired
    WithdrawalOrderRepository orderRepository;

    @BeforeAll
    static void provisionTenantSchemas() {
        for (String schema : new String[]{"tenant_acme", "tenant_globex"}) {
            Flyway.configure()
                    .dataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())
                    .schemas(schema)
                    .createSchemas(true)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration/tenant")
                    .load()
                    .migrate();
        }
    }

    @BeforeEach
    void strictNoFallback() {
        // Assert the REAL production path: no process-wide default tenant may mask a missing context.
        TenantContext.clear();
        TenantContext.setDefaultTenant(null);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    private String sign(String body) {
        String ts = Long.toString(Instant.now().getEpochSecond());
        return ts + ":" + SIGNER.sign(SECRET, "bank", "POST", PATH, ts,
                body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void headerlessWebhook_resolvesTenantFromBankRef_andSettlesInCorrectSchema() throws Exception {
        // Seed a SENT order for acme (in tenant_acme), with a tenant-encoded bankRef.
        String bankRef = BankRef.create("acme");
        Long acmeOrderId;
        try {
            TenantContext.set("acme");
            Wallet w = walletRepository.save(new Wallet(null, "user-acme", "Alice",
                    new BigDecimal("100"), new BigDecimal("30"), null));
            WithdrawalOrder saved = orderRepository.save(new WithdrawalOrder(null, "user-acme", w.getId(),
                    new BigDecimal("30"), WithdrawalState.SENT, bankRef, "k-acme", 1, Instant.now(), null));
            acmeOrderId = saved.getId();
        } finally {
            TenantContext.clear();
        }

        String body = "{\"bankRef\":\"" + bankRef + "\",\"result\":\"SETTLED\"}";
        String tsSig = sign(body);

        // NO X-Tenant-Id header — exactly what the bank sends. The real TenantFilter must NOT 400 this.
        mockMvc.perform(post(PATH)
                        .header("X-Timestamp", tsSig.split(":", 2)[0])
                        .header("X-Signature", tsSig.split(":", 2)[1])
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("APPLIED"));

        // Settlement landed in tenant_acme.
        try {
            TenantContext.set("acme");
            WithdrawalOrder after = orderRepository.findById(acmeOrderId).orElseThrow();
            assertEquals(WithdrawalState.SETTLED, after.getState(), "order settled in tenant_acme");
        } finally {
            TenantContext.clear();
        }

        // And NOTHING leaked into tenant_globex (the bankRef there is unknown).
        try {
            TenantContext.set("globex");
            assertEquals(true, orderRepository.findByBankRef(bankRef).isEmpty(),
                    "no settlement leaked into tenant_globex");
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void headerlessWebhook_legacyOrUnknownRef_returns200Ignored_notRouted500() throws Exception {
        // Legacy ref (SP4 wd-<uuid> shape) carries no tenant; header-less request has no context either.
        // Fail-closed gap: a routed lookup would throw on EMPTY_SENTINEL (500). Must return IGNORED.
        String body = "{\"bankRef\":\"wd-legacy-0001\",\"result\":\"SETTLED\"}";
        String tsSig = sign(body);

        mockMvc.perform(post(PATH)
                        .header("X-Timestamp", tsSig.split(":", 2)[0])
                        .header("X-Signature", tsSig.split(":", 2)[1])
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("IGNORED"));
    }
}
