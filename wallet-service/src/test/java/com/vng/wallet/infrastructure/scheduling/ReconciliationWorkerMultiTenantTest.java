package com.vng.wallet.infrastructure.scheduling;

import com.vng.wallet.application.ReconciliationService;
import com.vng.wallet.domain.BankClient;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WalletTransaction;
import com.vng.wallet.domain.WithdrawalOrder;
import com.vng.wallet.domain.WithdrawalOrderRepository;
import com.vng.wallet.domain.WithdrawalState;
import com.vng.wallet.infrastructure.bank.MockBankClient;
import com.vng.wallet.tenancy.TenantContext;
import com.vng.wallet.tenancy.TenantProvisioningService;
import com.vng.wallet.tenancy.master.TenantRegistry;
import com.vng.wallet.tenancy.master.TenantRegistryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * SP5 Task 7 (T9): the reconciliation worker runs on a {@code @Scheduled} thread with NO request and
 * NO {@link com.vng.wallet.tenancy.TenantFilter} → it must iterate the tenant registry itself and
 * {@code set/clear} the context per tenant so each tenant's orders are reconciled IN ITS OWN SCHEMA.
 *
 * <p>Proves: (1) two tenants each with a PENDING order → one worker pass reconciles BOTH, each in its
 * own schema (tenant a's order is never touched under tenant b's context, by construction of the
 * schema routing); (2) after the pass the ThreadLocal context is cleared (T4 — no leak to the next
 * scheduled run / pool thread).
 *
 * <p>Real MySQL (Testcontainers): schema-per-tenant + CREATE SCHEMA are DB-realism concerns.
 */
@SpringBootTest(properties = "wallet.bank.mock=true")
@Testcontainers
class ReconciliationWorkerMultiTenantTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired TenantProvisioningService provisioningService;
    @Autowired TenantRegistryRepository registryRepository;
    @Autowired WalletRepository walletRepository;
    @Autowired WithdrawalOrderRepository orderRepository;
    @Autowired ReconciliationService reconciliationService;
    @Autowired DataSource dataSource;
    @Autowired MockBankClient bank;

    private static final String[] TENANTS = {"alfa", "bravo"};

    @BeforeEach
    void clean() throws Exception {
        TenantContext.clear();
        TenantContext.setDefaultTenant(null);
        registryRepository.deleteAll();
        for (String t : TENANTS) {
            dropSchema("tenant_" + t);
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        TenantContext.clear();
        registryRepository.deleteAll();
        for (String t : TENANTS) {
            dropSchema("tenant_" + t);
        }
    }

    private void dropSchema(String schema) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema);
        }
    }

    /** Seed one PENDING order in the given tenant's schema (routed). */
    private long seedPendingOrder(String tenant, String bankRef) {
        TenantContext.set(tenant);
        try {
            Wallet w = walletRepository.save(new Wallet(null, "u-" + tenant, "Owner",
                    new BigDecimal("100"), new BigDecimal("40"), null));
            // ledger HOLD already reflected by held=40 above; create a PENDING order to reconcile.
            WithdrawalOrder saved = orderRepository.save(
                    WithdrawalOrder.create("u-" + tenant, w.getId(), new BigDecimal("40"),
                            "idem-" + tenant, bankRef));
            return saved.getId();
        } finally {
            TenantContext.clear();
        }
    }

    private WithdrawalState stateOf(String tenant, long orderId) {
        TenantContext.set(tenant);
        try {
            return orderRepository.findById(orderId).orElseThrow().getState();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void workerPass_reconcilesEveryActiveTenant_inItsOwnSchema_thenClearsContext() {
        provisioningService.provision("alfa");
        provisioningService.provision("bravo");
        // Bank settles alfa's ref, rejects bravo's ref → each must land in its own schema.
        bank.configure("wd.alfa.ref", BankClient.BankStatus.SETTLED);
        bank.configure("wd.bravo.ref", BankClient.BankStatus.REJECTED);
        long alfaOrder = seedPendingOrder("alfa", "wd.alfa.ref");
        long bravoOrder = seedPendingOrder("bravo", "wd.bravo.ref");

        // The multi-tenant worker pass: iterate registry, set/clear context per tenant.
        new MultiTenantReconciliationRunner(registryRepository, reconciliationService).runOnce();

        assertEquals(WithdrawalState.SETTLED, stateOf("alfa", alfaOrder), "alfa settled in tenant_alfa");
        assertEquals(WithdrawalState.FAILED, stateOf("bravo", bravoOrder), "bravo refunded in tenant_bravo");
        // T4: context cleared after the pass — no leak onto the scheduler thread.
        assertNull(TenantContext.get(), "context cleared after worker pass");
    }
}
