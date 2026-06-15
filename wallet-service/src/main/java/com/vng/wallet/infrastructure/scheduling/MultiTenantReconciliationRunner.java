package com.vng.wallet.infrastructure.scheduling;

import com.vng.wallet.application.ReconciliationService;
import com.vng.wallet.tenancy.TenantContext;
import com.vng.wallet.tenancy.master.TenantRegistry;
import com.vng.wallet.tenancy.master.TenantRegistryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * SP5 Task 7 (T9): the multi-tenant fan-out for the reconciliation worker.
 *
 * <p>The {@code @Scheduled} worker runs on a thread with NO request and NO
 * {@link com.vng.wallet.tenancy.TenantFilter}, so it cannot inherit a tenant from a header. It must
 * iterate the tenant registry itself and {@code set/clear} {@link TenantContext} per tenant — each
 * {@link ReconciliationService#reconcile()} then scans {@code withdrawal_order} in THAT tenant's
 * schema (schema-per-tenant routing). One tenant's orders are never reconciled under another's
 * context, by construction.
 *
 * <p>Invariants:
 * <ul>
 *   <li><b>set/clear in finally per tenant (T4):</b> a reused scheduler thread never leaks a prior
 *       tenant's context into the next tenant — or into the next scheduled run.</li>
 *   <li><b>failure isolation:</b> one tenant's reconcile blowing up does NOT stop the loop; it is
 *       logged and the next tenant still runs (a bad tenant must not starve the fleet).</li>
 *   <li><b>baseline fallback:</b> when the registry holds NO ACTIVE tenants (single-schema baseline,
 *       e.g. pre-SP5 integration tests), fall back to one plain {@code reconcile()} run on whatever
 *       context is already effective — preserving SP4 behavior without provisioning a tenant.</li>
 * </ul>
 */
public class MultiTenantReconciliationRunner {

    private static final Logger log = LoggerFactory.getLogger(MultiTenantReconciliationRunner.class);

    private final TenantRegistryRepository registryRepository;
    private final ReconciliationService reconciliationService;

    public MultiTenantReconciliationRunner(TenantRegistryRepository registryRepository,
                                           ReconciliationService reconciliationService) {
        this.registryRepository = registryRepository;
        this.reconciliationService = reconciliationService;
    }

    /** One reconciliation pass across all ACTIVE tenants (or the baseline single schema if none). */
    public void runOnce() {
        List<TenantRegistry> active;
        try {
            active = registryRepository.findByStatus(TenantRegistry.Status.ACTIVE);
        } catch (Exception e) {
            // Registry unreadable. In PRODUCTION this is a real fault → rethrow so the round's logger
            // surfaces it (and prod never has a process-wide default tenant, so we do NOT silently
            // reconcile something). In the single-schema BASELINE (a process-wide default tenant is
            // installed by tests that don't provision a master registry), treat it as "no tenants"
            // and reconcile that one schema — preserving pre-SP5 behavior.
            if (TenantContext.effective() == null) {
                throw e;
            }
            active = Collections.emptyList();
        }
        if (active.isEmpty()) {
            // Single-schema baseline: nothing in the registry → reconcile the current/default context.
            reconciliationService.reconcile();
            return;
        }
        for (TenantRegistry tenant : active) {
            try {
                TenantContext.set(tenant.getTenantId());
                reconciliationService.reconcile();
            } catch (Exception e) {
                // One tenant failing must not stop the fleet — log + continue (next round retries).
                log.warn("reconciliation failed for tenant={} (will retry next round): {}",
                        tenant.getTenantId(), e.toString());
            } finally {
                TenantContext.clear(); // T4 — never leak this tenant into the next iteration.
            }
        }
    }
}
