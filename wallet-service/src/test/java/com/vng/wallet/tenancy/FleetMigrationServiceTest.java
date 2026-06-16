package com.vng.wallet.tenancy;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP5 Task 6 (T8): fleet migration — run a new migration version across every ACTIVE tenant schema.
 *
 * <p>Proves the design's invariants (§6.2/§6.3): per-schema Flyway is independent + idempotent;
 * a failure in one tenant is ISOLATED (that tenant → MIGRATION_FAILED) and does NOT stop the job
 * (other tenants still advance); re-running skips the already-migrated tenants and retries the
 * failed one. The migration used is an EXPAND-only (ADD nullable column) change so mixed-version
 * tenants stay compatible.
 *
 * <p>Runs on real MySQL (Testcontainers): CREATE SCHEMA + per-schema {@code flyway_schema_history}
 * are DB-realism concerns. Tenants are provisioned at the baseline V1..V4 (V4 = SP6 transfer columns);
 * the fleet job points at a {@code fleet_v4} location (V1..V4 + a V5 stand-in display_name) — the
 * realistic "ship a new version to the fleet".
 */
@SpringBootTest
@Testcontainers
class FleetMigrationServiceTest {

    private static final String FLEET_V4_LOCATION = "classpath:db/migration/fleet_v4";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        // root: fleet migration needs CREATE SCHEMA / DDL privilege (the default `test` user lacks it).
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    TenantProvisioningService provisioningService;

    @Autowired
    TenantRegistryRepository registryRepository;

    @Autowired
    DataSource dataSource;

    private static final String[] TENANTS = {"alfa", "bravo", "charlie"};

    @BeforeEach
    void clean() throws Exception {
        TenantContext.clear();
        TenantContext.setDefaultTenant(null);
        registryRepository.deleteAll();
        for (String t : TENANTS) {
            dropSchema(TenantSchemas.schemaFor(t));
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        TenantContext.clear();
        registryRepository.deleteAll();
        for (String t : TENANTS) {
            dropSchema(TenantSchemas.schemaFor(t));
        }
    }

    private void dropSchema(String schema) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS " + schema);
        }
    }

    private void exec(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private boolean columnExists(String schema, String table, String column) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='" + schema
                            + "' AND table_name='" + table + "' AND column_name='" + column + "'");
            rs.next();
            return rs.getInt(1) > 0;
        }
    }

    private int latestVersion(String schema) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            ResultSet rs = st.executeQuery(
                    "SELECT MAX(CAST(version AS UNSIGNED)) FROM " + schema + ".flyway_schema_history"
                            + " WHERE success = 1");
            rs.next();
            return rs.getInt(1);
        }
    }

    private TenantRegistry.Status statusOf(String tenantId) {
        return registryRepository.findById(tenantId).orElseThrow().getStatus();
    }

    private FleetMigrationService fleetAt(String location) {
        return new FleetMigrationService(registryRepository, dataSource, location);
    }

    @Test
    void migrateAll_advancesEveryActiveTenant_toNewVersion() throws Exception {
        for (String t : TENANTS) {
            provisioningService.provision(t);
        }

        FleetMigrationResult result = fleetAt(FLEET_V4_LOCATION).migrateAll();

        assertEquals(3, result.succeeded(), "all three ACTIVE tenants migrated");
        assertEquals(0, result.failed());
        for (String t : TENANTS) {
            String schema = TenantSchemas.schemaFor(t);
            assertTrue(columnExists(schema, "wallet", "display_name"), t + " got V5 column");
            assertEquals(5, latestVersion(schema), t + " at version 5");
            assertEquals(TenantRegistry.Status.ACTIVE, statusOf(t), t + " stays ACTIVE");
        }
    }

    @Test
    void migrateAll_isolatesFailure_othersStillAdvance_jobDoesNotStop() throws Exception {
        for (String t : TENANTS) {
            provisioningService.provision(t);
        }
        // Make bravo's V5 fail: pre-add the column V5 tries to add → duplicate-column error.
        exec("ALTER TABLE " + TenantSchemas.schemaFor("bravo") + ".wallet ADD COLUMN display_name VARCHAR(255)");

        FleetMigrationResult result = fleetAt(FLEET_V4_LOCATION).migrateAll();

        assertEquals(2, result.succeeded(), "alfa + charlie advance despite bravo failing");
        assertEquals(1, result.failed());

        // alfa, charlie advanced + stay ACTIVE.
        for (String t : new String[]{"alfa", "charlie"}) {
            assertEquals(5, latestVersion(TenantSchemas.schemaFor(t)), t + " at V5");
            assertEquals(TenantRegistry.Status.ACTIVE, statusOf(t));
        }
        // bravo flagged for ops, NOT silently ACTIVE, never half-migrated past V4.
        assertEquals(TenantRegistry.Status.MIGRATION_FAILED, statusOf("bravo"));
        assertEquals(4, latestVersion(TenantSchemas.schemaFor("bravo")), "bravo stuck at V4");
    }

    @Test
    void rerun_skipsDone_retriesFailed_idempotent() throws Exception {
        for (String t : TENANTS) {
            provisioningService.provision(t);
        }
        exec("ALTER TABLE " + TenantSchemas.schemaFor("bravo") + ".wallet ADD COLUMN display_name VARCHAR(255)");

        // First run: bravo fails.
        fleetAt(FLEET_V4_LOCATION).migrateAll();
        assertEquals(TenantRegistry.Status.MIGRATION_FAILED, statusOf("bravo"));

        // Fix bravo (drop the conflicting column) then re-run the SAME job.
        exec("ALTER TABLE " + TenantSchemas.schemaFor("bravo") + ".wallet DROP COLUMN display_name");
        FleetMigrationResult rerun = fleetAt(FLEET_V4_LOCATION).migrateAll();

        // alfa + charlie already at V5 → Flyway no-ops them (idempotent); bravo retried + succeeds.
        assertEquals(3, rerun.succeeded(), "all three converge on re-run");
        assertEquals(0, rerun.failed());
        for (String t : TENANTS) {
            assertEquals(5, latestVersion(TenantSchemas.schemaFor(t)), t + " converged on V5");
            assertEquals(TenantRegistry.Status.ACTIVE, statusOf(t));
        }
        assertFalse(columnExists("information_schema", "wallet", "nonexistent"));
    }
}
