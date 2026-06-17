package com.vng.wallet.tenancy;

import com.vng.wallet.idempotency.IdempotencyRecord;
import com.vng.wallet.idempotency.IdempotencyStore;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP7 Bước 1 Task 1 (⭐ routing): idempotency_record nằm PER-TENANT SCHEMA (L2), routed như mọi entity
 * khác qua {@link TenantContext} (SP5). Hai schema tenant thật trên MySQL (Testcontainers) được Flyway
 * migrate (kể cả V5). Cùng một idempotency_key tồn tại trong tenant a KHÔNG được tenant b nhìn thấy —
 * vì UNIQUE(key) là "toàn cục TRONG schema tenant", không xuyên tenant.
 */
@SpringBootTest
@Testcontainers
class IdempotencyStoreTenantIsolationIntegrationTest {

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
    IdempotencyStore store;

    @BeforeAll
    static void provisionTenantSchemas() {
        for (String schema : new String[]{"tenant_a", "tenant_b"}) {
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
        TenantContext.clear();
        TenantContext.setDefaultTenant(null);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void recordOfTenantAIsInvisibleToTenantB() {
        TenantContext.set("a");
        store.save(new IdempotencyRecord("shared-key", "TOPUP", "fp-a", null, Instant.now()));
        assertTrue(store.find("shared-key").isPresent(), "tenant a sees its own record");

        TenantContext.set("b");
        assertTrue(store.find("shared-key").isEmpty(),
                "idempotency_key lives PER-TENANT schema — tenant b must NOT see tenant a's record");
    }

    @Test
    void sameKeyCanExistIndependentlyInEachTenantSchema() {
        TenantContext.set("a");
        store.save(new IdempotencyRecord("dup-across-tenants", "TOPUP", "fp-a", null, Instant.now()));

        // ⭐ same key, DIFFERENT tenant schema: no UNIQUE collision (UNIQUE is per-schema, not global).
        TenantContext.set("b");
        store.save(new IdempotencyRecord("dup-across-tenants", "WITHDRAW", "fp-b", null, Instant.now()));
        assertTrue(store.find("dup-across-tenants").isPresent(), "tenant b can claim the same key freely");
    }
}
