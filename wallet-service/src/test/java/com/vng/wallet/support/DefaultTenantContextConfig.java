package com.vng.wallet.support;

import com.vng.wallet.tenancy.TenantContext;
import com.vng.wallet.tenancy.TenantSchemas;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.test.context.TestConfiguration;

/**
 * Test support (SP5 Task 4): with schema-per-tenant routing now live on the tenant EMF, any DB
 * access on a thread WITHOUT a tenant context fails closed. Pre-SP5 tests reach the DB directly
 * (services/repositories, thread pools, the @Scheduled worker, the Kafka consumer) — none of which
 * pass through {@link com.vng.wallet.tenancy.TenantFilter}, so none set a context.
 *
 * <p>This config installs the {@code default} tenant as the process-wide fallback
 * ({@link TenantContext#setDefaultTenant}); since {@code default} maps to the pool's default schema
 * (no SET SCHEMA), those tests keep running on the single Task-1 baseline schema — exactly the
 * pre-SP5 behavior. Static fallback (not ThreadLocal) so spawned/background threads are covered too.
 *
 * <p>Production never imports this → an unset thread stays fail-closed.
 */
@TestConfiguration
public class DefaultTenantContextConfig {

    @PostConstruct
    void installDefaultTenant() {
        TenantContext.setDefaultTenant(TenantSchemas.DEFAULT_TENANT);
    }

    @PreDestroy
    void removeDefaultTenant() {
        TenantContext.setDefaultTenant(null);
    }
}
