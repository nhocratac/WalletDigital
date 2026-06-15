package com.vng.wallet.tenancy;

import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP5 Task 5 (T6, T7): EAGER provisioning — onboarding a new tenant creates its schema and runs
 * Flyway so the schema is ACTIVE & fully built BEFORE the tenant's first request.
 *
 * <p>Runs against real MySQL (Testcontainers): CREATE SCHEMA + Flyway are DB-realism concerns H2
 * cannot faithfully prove. Asserts the full workflow (registry PROVISIONING → CREATE SCHEMA →
 * flyway.migrate → ACTIVE), that a failed migration leaves MIGRATION_FAILED (never half-ACTIVE),
 * that the provisioned schema is immediately usable via routing, and that re-provisioning an
 * existing tenant is rejected.
 */
@SpringBootTest
@Testcontainers
class TenantProvisioningServiceTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        // root: provisioning needs CREATE SCHEMA privilege (the default `test` user lacks it).
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    TenantProvisioningService provisioningService;

    @Autowired
    TenantRegistryRepository registryRepository;

    @Autowired
    WalletRepository walletRepository;

    @Autowired
    DataSource dataSource;

    @BeforeEach
    void clean() throws Exception {
        TenantContext.clear();
        TenantContext.setDefaultTenant(null);
        registryRepository.deleteAll();
        dropSchema("tenant_globex");
        dropSchema("tenant_usable");
    }

    @AfterEach
    void cleanup() throws Exception {
        TenantContext.clear();
        registryRepository.deleteAll();
        dropSchema("tenant_globex");
        dropSchema("tenant_usable");
    }

    private void dropSchema(String schema) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema);
        }
    }

    private boolean tableExists(String schema, String table) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='"
                            + schema + "' AND table_name='" + table + "'");
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    @Test
    void provision_createsSchema_migrates_andMarksActive() throws Exception {
        provisioningService.provision("globex");

        Optional<TenantRegistry> reg = registryRepository.findById("globex");
        assertTrue(reg.isPresent(), "registry row written");
        assertEquals("tenant_globex", reg.get().getSchemaName());
        assertEquals(TenantRegistry.Status.ACTIVE, reg.get().getStatus(), "ends ACTIVE");

        // Flyway built the standard tables in the new schema.
        assertTrue(tableExists("tenant_globex", "wallet"), "wallet table provisioned");
        assertTrue(tableExists("tenant_globex", "withdrawal_order"), "withdrawal_order table provisioned");
    }

    @Test
    void afterProvision_schemaIsUsableViaRouting() {
        provisioningService.provision("usable");

        TenantContext.set("usable");
        Wallet saved = walletRepository.save(Wallet.createNew("user-x", "Xavier"));
        assertTrue(saved.getId() != null, "wallet created in newly provisioned schema");
        TenantContext.clear();
    }

    @Test
    void migrationFailure_marksMigrationFailed_notActive() throws Exception {
        // Inject a Flyway location holding intentionally invalid SQL so migrate() fails AFTER the
        // registry row exists (a non-existent location would silently run 0 migrations).
        TenantProvisioningService failing = new TenantProvisioningService(
                registryRepository, dataSource, "classpath:db/migration/broken");

        assertThrows(Exception.class, () -> failing.provision("globex"));

        Optional<TenantRegistry> reg = registryRepository.findById("globex");
        assertTrue(reg.isPresent(), "registry row written before migration attempt");
        assertNotEquals(TenantRegistry.Status.ACTIVE, reg.get().getStatus(),
                "must NOT be left half-ACTIVE on migration failure");
        assertEquals(TenantRegistry.Status.MIGRATION_FAILED, reg.get().getStatus());
    }

    @Test
    void reprovisioningExistingTenant_isRejected() {
        provisioningService.provision("globex");
        assertThrows(TenantAlreadyExistsException.class, () -> provisioningService.provision("globex"));
    }
}
