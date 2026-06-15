package com.vng.wallet.tenancy;

import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.infrastructure.persistence.SpringDataWalletJpa;
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

import javax.sql.DataSource;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP5 Task 4 (⭐ T1, T3): the HEART of SP5 — prove tenant isolation BY CONSTRUCTION.
 *
 * <p>Two real tenant schemas ({@code tenant_a}, {@code tenant_b}) are provisioned on MySQL
 * (Testcontainers) via Flyway (Task 5 provisioning service does not exist yet, so the test
 * migrates them directly). Then, switching only {@link TenantContext}:
 * <ul>
 *   <li>context=a creates a wallet; context=b sees an EMPTY list (no cross-tenant leak);</li>
 *   <li>⭐ a BARE query missing any WHERE filter ({@code findAll()}) under context=a returns
 *       ONLY tenant a's rows — proving "forgot the WHERE" is still safe;</li>
 *   <li>⭐ thread-reuse: the SAME thread serves a then b — b does NOT see a;</li>
 *   <li>fail-closed: with an EMPTY context, any DB operation throws (it must NOT silently point
 *       at a schema holding real data).</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
class TenantIsolationIntegrationTest {

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
    WalletRepository walletRepository;

    @Autowired
    SpringDataWalletJpa walletJpa;

    @Autowired
    DataSource dataSource;

    @BeforeAll
    static void provisionTenantSchemas() {
        // Task 5 provisioning is not built yet; migrate the two tenant schemas directly.
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
        // This test asserts REAL routing + fail-closed; ensure no process-wide default tenant from
        // another (cached) test context leaks in to mask the empty-context case.
        TenantContext.clear();
        TenantContext.setDefaultTenant(null);
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void tenantBDoesNotSeeTenantAWallets() {
        TenantContext.set("a");
        walletRepository.save(Wallet.createNew("user-a", "Alice"));

        TenantContext.set("b");
        List<Wallet> bWallets = walletRepository.findAllByUserId("user-a");
        assertTrue(bWallets.isEmpty(), "tenant b must NOT see tenant a's wallet");
    }

    @Test
    void bareQueryWithoutWhereStillOnlySeesOneTenant() {
        TenantContext.set("a");
        walletRepository.save(Wallet.createNew("isolated-user", "OnlyA"));
        long countA = walletJpa.findAll().stream()
                .filter(w -> "isolated-user".equals(w.getUserId())).count();
        assertEquals(1, countA);

        // ⭐ findAll() has NO WHERE — under context=b it must still see ZERO of a's rows.
        TenantContext.set("b");
        long leaked = walletJpa.findAll().stream()
                .filter(w -> "isolated-user".equals(w.getUserId())).count();
        assertEquals(0, leaked, "bare findAll() must be isolated by SCHEMA, not by WHERE");
    }

    @Test
    void failClosedWhenNoTenantContext() {
        TenantContext.clear();
        // Empty context must NOT silently route to a schema with real data — it must throw.
        assertThrows(Exception.class, () -> walletJpa.findAll());
    }
}
