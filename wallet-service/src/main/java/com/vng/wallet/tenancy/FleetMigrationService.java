package com.vng.wallet.tenancy;

import com.vng.wallet.tenancy.master.TenantRegistry;
import com.vng.wallet.tenancy.master.TenantRegistryRepository;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * SP5 Task 6 (T8): fleet migration — apply the latest {@code db/migration/tenant} version set to
 * EVERY tenant schema that is live.
 *
 * <p>Migrating N live schemas is NOT atomic (design §6.2), so this job is built to the same
 * principles as the SP4 reconciliation worker:
 * <ul>
 *   <li><b>Per-schema, independent:</b> one {@link Flyway} run per schema — schema #37 failing does
 *       not touch the other 49.</li>
 *   <li><b>Failure isolated, job never stops:</b> a per-tenant exception is caught, the tenant is
 *       flagged {@link TenantRegistry.Status#MIGRATION_FAILED} (for ops — never left silently ACTIVE
 *       at a half-applied version, MySQL has no transactional DDL), and the loop CONTINUES.</li>
 *   <li><b>Idempotent + resumable:</b> Flyway's per-schema {@code flyway_schema_history} means a
 *       re-run no-ops the already-migrated schemas and only retries the failed ones. We
 *       {@code repair()} before {@code migrate()} so a failed-version marker left by a previous run
 *       (MySQL DDL is non-transactional) is cleaned up, letting the retry actually re-apply.</li>
 * </ul>
 *
 * <p>The migration set is expand/contract-style (additive, backward-compatible — §6.3) so tenants
 * at the old and new versions both keep working with the currently-deployed code during a fleet
 * rollout. Reuses the single application {@link DataSource} (one pool, T10); driven by an admin
 * trigger ({@code POST /admin/tenants/migrate}).
 */
@Service
public class FleetMigrationService {

    private static final Logger log = LoggerFactory.getLogger(FleetMigrationService.class);

    private final TenantRegistryRepository registryRepository;
    private final DataSource dataSource;
    private final String tenantMigrationLocation;

    public FleetMigrationService(
            TenantRegistryRepository registryRepository,
            DataSource dataSource,
            @Value("${spring.flyway.locations:classpath:db/migration/tenant}") String tenantMigrationLocation) {
        this.registryRepository = registryRepository;
        this.dataSource = dataSource;
        this.tenantMigrationLocation = tenantMigrationLocation;
    }

    /**
     * Migrate every ACTIVE (or previously MIGRATION_FAILED — resume) tenant schema to the latest
     * version. Never throws on a per-tenant failure; the result reports successes vs failures.
     */
    public FleetMigrationResult migrateAll() {
        List<TenantRegistry> active = registryRepository.findByStatus(TenantRegistry.Status.ACTIVE);
        List<TenantRegistry> failed = registryRepository.findByStatus(TenantRegistry.Status.MIGRATION_FAILED);

        List<TenantRegistry> targets = new ArrayList<>(active);
        targets.addAll(failed); // resume tenants a prior run left flagged

        int succeeded = 0;
        List<String> failedTenants = new ArrayList<>();

        for (TenantRegistry tenant : targets) {
            String schema = tenant.getSchemaName();
            try {
                Flyway flyway = Flyway.configure()
                        .dataSource(dataSource)
                        .schemas(schema)
                        .createSchemas(true)
                        .defaultSchema(schema)
                        .locations(tenantMigrationLocation)
                        .load();
                // Clean up any failed-version marker a prior run left (MySQL DDL is non-transactional)
                // so a retry can re-apply the migration; a no-op for healthy schemas.
                flyway.repair();
                flyway.migrate();

                if (tenant.getStatus() != TenantRegistry.Status.ACTIVE) {
                    markStatus(tenant.getTenantId(), TenantRegistry.Status.ACTIVE);
                }
                succeeded++;
            } catch (RuntimeException ex) {
                log.error("fleet migration failed for tenant {} (schema {}) — flagging MIGRATION_FAILED, "
                        + "continuing with the rest of the fleet", tenant.getTenantId(), schema, ex);
                markStatus(tenant.getTenantId(), TenantRegistry.Status.MIGRATION_FAILED);
                failedTenants.add(tenant.getTenantId());
            }
        }

        log.info("fleet migration done: {} succeeded, {} failed {}", succeeded, failedTenants.size(),
                failedTenants);
        return new FleetMigrationResult(succeeded, failedTenants.size(), List.copyOf(failedTenants));
    }

    private void markStatus(String tenantId, TenantRegistry.Status status) {
        registryRepository.findById(tenantId).ifPresent(r -> {
            r.setStatus(status);
            registryRepository.save(r);
        });
    }
}
