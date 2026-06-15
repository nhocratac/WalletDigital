package com.vng.wallet.tenancy;

import java.util.List;

/**
 * SP5 Task 6 (T8): outcome of a {@link FleetMigrationService#migrateAll()} run.
 *
 * <p>The job NEVER throws on a per-tenant failure (a failure is isolated, not fatal — design §6.2),
 * so the caller inspects this to know how many schemas advanced vs were flagged
 * {@code MIGRATION_FAILED} for ops, plus the explicit failed-tenant ids for logging/alerting.
 */
public record FleetMigrationResult(int succeeded, int failed, List<String> failedTenants) {
}
