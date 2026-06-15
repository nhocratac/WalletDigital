package com.vng.wallet.tenancy.master;

import com.vng.wallet.tenancy.TenantContext;
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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP5 Task 3 (T5): the master persistence unit holds data ABOUT tenants. It must be
 * readable WITHOUT a TenantContext (it is non-routed) — that is the whole point of having a
 * separate master EMF: routing has to look up tenantId→schema BEFORE any tenant is selected.
 *
 * <p>Runs against a real MySQL (Testcontainers) so we prove the master EMF + its own Flyway
 * location (db/migration/master) actually built {@code master.tenant_registry}, independent of
 * the tenant schema. {@code @BeforeEach} asserts NO tenant context is set — these reads/writes
 * happen with an empty TenantContext on purpose (chicken-egg).
 */
@SpringBootTest
@Testcontainers
class TenantRegistryRepositoryTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        // root: the master/tenant flyway needs CREATE SCHEMA privilege (realistic — provisioning
        // in Task 5 also requires it). The default `test` user is scoped to the `test` db only.
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    TenantRegistryRepository repository;

    @BeforeEach
    void noTenantContext() {
        // Sanity: master must be readable with an EMPTY tenant context (non-routed).
        TenantContext.clear();
        repository.deleteAll();
    }

    @AfterEach
    void cleanup() {
        repository.deleteAll();
        TenantContext.clear();
    }

    @Test
    void saveAndLoad_withoutTenantContext() {
        assertTrue(TenantContext.get() == null, "precondition: master read/write with NO tenant context");

        repository.save(new TenantRegistry("acme", "tenant_acme",
                TenantRegistry.Status.PROVISIONING, Instant.now()));

        Optional<TenantRegistry> found = repository.findById("acme");
        assertTrue(found.isPresent());
        assertEquals("tenant_acme", found.get().getSchemaName());
        assertEquals(TenantRegistry.Status.PROVISIONING, found.get().getStatus());
    }

    @Test
    void findByStatus_returnsOnlyMatching() {
        repository.save(new TenantRegistry("acme", "tenant_acme",
                TenantRegistry.Status.ACTIVE, Instant.now()));
        repository.save(new TenantRegistry("globex", "tenant_globex",
                TenantRegistry.Status.PROVISIONING, Instant.now()));
        repository.save(new TenantRegistry("initech", "tenant_initech",
                TenantRegistry.Status.ACTIVE, Instant.now()));

        List<TenantRegistry> active = repository.findByStatus(TenantRegistry.Status.ACTIVE);
        assertEquals(2, active.size());
        assertTrue(active.stream().allMatch(t -> t.getStatus() == TenantRegistry.Status.ACTIVE));
        assertTrue(active.stream().map(TenantRegistry::getTenantId).toList()
                .containsAll(List.of("acme", "initech")));

        List<TenantRegistry> provisioning = repository.findByStatus(TenantRegistry.Status.PROVISIONING);
        assertEquals(1, provisioning.size());
        assertEquals("globex", provisioning.get(0).getTenantId());
    }

    @Test
    void tenantId_isPrimaryKey_duplicateRejected() {
        repository.save(new TenantRegistry("acme", "tenant_acme",
                TenantRegistry.Status.ACTIVE, Instant.now()));
        repository.flush();

        // Same tenant_id (PK) inserted again must violate the constraint.
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> {
            repository.saveNew(new TenantRegistry("acme", "tenant_acme_dup",
                    TenantRegistry.Status.ACTIVE, Instant.now()));
            repository.flush();
        });
    }

    @Test
    void empty_whenMissing() {
        assertFalse(repository.findById("nope").isPresent());
    }
}
