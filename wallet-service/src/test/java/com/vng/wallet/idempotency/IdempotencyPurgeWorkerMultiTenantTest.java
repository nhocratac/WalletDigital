package com.vng.wallet.idempotency;

import com.vng.wallet.tenancy.TenantContext;
import com.vng.wallet.tenancy.TenantProvisioningService;
import com.vng.wallet.tenancy.master.TenantRegistryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP7 Bước 1 Task 6 (L2): TTL purge worker chạy per-tenant. Như reconciliation worker, purge chạy trên
 * thread {@code @Scheduled} không có request → phải tự lặp registry + set/clear {@link TenantContext}
 * để purge {@code idempotency_record} TRONG ĐÚNG schema từng tenant (per-tenant-schema, SP5).
 *
 * <p>Chứng minh: (1) hai tenant, mỗi tenant có 1 record CŨ (quá TTL) + 1 record MỚI → một vòng worker
 * purge record cũ ở CẢ hai tenant, trong schema riêng từng tenant, và GIỮ record mới; (2) context được
 * clear sau vòng (T4 — không rò sang thread pool / vòng kế).
 *
 * <p>MySQL thật (Testcontainers): schema-per-tenant + routing là chuyện DB-realism.
 */
@SpringBootTest(properties = {
        "wallet.bank.mock=true",
        "wallet.idempotency.ttl-days=7"
})
@Testcontainers
class IdempotencyPurgeWorkerMultiTenantTest {

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
    @Autowired IdempotencyStore idempotencyStore;
    @Autowired DataSource dataSource;
    @Value("${wallet.idempotency.ttl-days}") int ttlDays;

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

    /** Seed an expired + a fresh idempotency_record in the given tenant's schema (routed). */
    private void seedRecords(String tenant, Instant now) {
        TenantContext.set(tenant);
        try {
            // CŨ hơn TTL → phải purge.
            idempotencyStore.save(new IdempotencyRecord(
                    "old-" + tenant, "TOPUP", "fp-old", "tx-old",
                    now.minus(Duration.ofDays(ttlDays + 1))));
            // MỚI hơn TTL → phải giữ.
            idempotencyStore.save(new IdempotencyRecord(
                    "fresh-" + tenant, "TOPUP", "fp-fresh", "tx-fresh",
                    now.minus(Duration.ofDays(1))));
        } finally {
            TenantContext.clear();
        }
    }

    private boolean exists(String tenant, String key) {
        TenantContext.set(tenant);
        try {
            return idempotencyStore.find(key).isPresent();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void purgePass_removesExpiredEveryActiveTenant_inItsOwnSchema_keepsFresh_thenClearsContext() {
        provisioningService.provision("alfa");
        provisioningService.provision("bravo");
        Instant now = Instant.now();
        seedRecords("alfa", now);
        seedRecords("bravo", now);

        // The multi-tenant purge pass: iterate registry, set/clear context per tenant.
        IdempotencyPurgeWorker worker =
                new IdempotencyPurgeWorker(idempotencyStore, registryRepository, ttlDays);
        worker.runOnce(now);

        // Expired purged in BOTH tenants' own schema; fresh kept.
        assertTrue(!exists("alfa", "old-alfa"), "alfa expired purged in tenant_alfa");
        assertTrue(!exists("bravo", "old-bravo"), "bravo expired purged in tenant_bravo");
        assertTrue(exists("alfa", "fresh-alfa"), "alfa fresh kept in tenant_alfa");
        assertTrue(exists("bravo", "fresh-bravo"), "bravo fresh kept in tenant_bravo");
        // T4: context cleared after the pass — no leak onto the scheduler thread.
        assertNull(TenantContext.get(), "context cleared after purge pass");
    }
}
