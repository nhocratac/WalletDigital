package com.vng.wallet.tenancy;

import com.vng.wallet.tenancy.master.TenantRegistry;
import com.vng.wallet.tenancy.master.TenantRegistryRepository;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Optional;

/**
 * SP5 Task 5 (T6, T7): EAGER tenant onboarding. A tenant's schema is created and migrated at
 * REGISTRATION time, not lazily on the first request — so a real user never has to wait for (or
 * race) schema creation in the hot path; their schema is always ACTIVE and fully built.
 *
 * <p>Workflow (design §5.2):
 * <ol>
 *   <li>INSERT {@code tenant_registry(tenantId, tenant_&lt;id&gt;, status=PROVISIONING)} — via
 *       {@link TenantRegistryRepository#saveNew} so a duplicate PK hits the DB constraint (→ 409),
 *       never a silent merge/overwrite.</li>
 *   <li>{@code CREATE SCHEMA tenant_&lt;id&gt;} + {@code flyway.migrate(schema=tenant_&lt;id&gt;)} —
 *       runs the SAME versioned {@code db/migration/tenant} V1..Vn (T7: one source of truth → every
 *       schema converges to the identical structure).</li>
 *   <li>UPDATE status = ACTIVE.</li>
 * </ol>
 * If migration fails half-way the row is flagged {@code MIGRATION_FAILED} (never left half-ACTIVE)
 * for ops, and the failure is rethrown.
 *
 * <p>Reuses the application {@link DataSource} (one pool, T10). The Flyway here is created
 * imperatively (NOT a {@code Flyway} @Bean) for the same reason as the master unit: a {@code Flyway}
 * bean would trip Boot's {@code @ConditionalOnMissingBean(Flyway)} and disable the default tenant
 * Flyway.
 */
@Service
public class TenantProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioningService.class);

    private final TenantRegistryRepository registryRepository;
    private final DataSource dataSource;
    private final String tenantMigrationLocation;

    public TenantProvisioningService(
            TenantRegistryRepository registryRepository,
            DataSource dataSource,
            @Value("${spring.flyway.locations:classpath:db/migration/tenant}") String tenantMigrationLocation) {
        this.registryRepository = registryRepository;
        this.dataSource = dataSource;
        this.tenantMigrationLocation = tenantMigrationLocation;
    }

    /**
     * Onboard a new tenant: register, create + migrate its schema, mark ACTIVE.
     *
     * @throws TenantAlreadyExistsException if the tenant id is already registered (→ 409)
     * @throws RuntimeException             if schema creation/migration fails (registry left
     *                                      {@code MIGRATION_FAILED})
     */
    public void provision(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        String id = tenantId.trim();
        String schema = TenantSchemas.schemaFor(id);

        // Step 1: register PROVISIONING (saveNew → real INSERT, duplicate PK = DB constraint).
        try {
            registryRepository.saveNew(new TenantRegistry(
                    id, schema, TenantRegistry.Status.PROVISIONING, Instant.now()));
        } catch (DataIntegrityViolationException dup) {
            throw new TenantAlreadyExistsException(id);
        }

        // Steps 2-3: CREATE SCHEMA + Flyway migrate the standard tenant structure.
        try {
            Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .createSchemas(true)
                    .defaultSchema(schema)
                    .locations(tenantMigrationLocation)
                    .load()
                    .migrate();
        } catch (RuntimeException ex) {
            log.error("provisioning migration failed for tenant {} (schema {}) — marking MIGRATION_FAILED",
                    id, schema, ex);
            markStatus(id, TenantRegistry.Status.MIGRATION_FAILED);
            throw ex;
        }

        // Step 4: ACTIVE.
        markStatus(id, TenantRegistry.Status.ACTIVE);
        log.info("provisioned tenant {} → schema {} (ACTIVE)", id, schema);
    }

    private void markStatus(String tenantId, TenantRegistry.Status status) {
        Optional<TenantRegistry> reg = registryRepository.findById(tenantId);
        if (reg.isPresent()) {
            TenantRegistry r = reg.get();
            r.setStatus(status);
            registryRepository.save(r);
        }
    }
}
