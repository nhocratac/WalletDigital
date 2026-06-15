package com.vng.wallet.tenancy;

/**
 * SP5 Task 4: the single place that maps a tenant id to its physical schema name, plus the two
 * sentinel identifiers the routing layer treats specially.
 *
 * <p>Naming (Quyết định khoá #2): tenant {@code acme} → schema {@code tenant_acme}.
 *
 * <p>Two sentinels exist so routing is correct in BOTH directions:
 * <ul>
 *   <li>{@link #EMPTY_SENTINEL} — returned when {@link TenantContext} is empty. It is NOT a real
 *       schema; the connection provider issues {@code SET SCHEMA __no_tenant__}, which fails loudly.
 *       This is the FAIL-CLOSED guarantee: "forgot to set tenant" never silently points at a schema
 *       holding real data.</li>
 *   <li>{@link #DEFAULT_TENANT} → {@link #DEFAULT_SENTINEL} — the single-schema baseline tenant used
 *       by every pre-SP5 test (HTTP requests carry {@code X-Tenant-Id: default}). Its connections are
 *       left at the pool's DEFAULT schema (no {@code SET SCHEMA}), exactly where the Task 1 single-
 *       schema Flyway built the tables. Lets all SP1–SP4 tests stay green without provisioning.</li>
 * </ul>
 */
public final class TenantSchemas {

    /** Resolver returns this when TenantContext is empty — a schema that does NOT exist (fail-closed). */
    public static final String EMPTY_SENTINEL = "__no_tenant__";

    /** The single-schema baseline tenant id (pre-SP5 tests / default datasource schema). */
    public static final String DEFAULT_TENANT = "default";

    /** Provider leaves the connection on the pool's default schema (no SET SCHEMA) for this. */
    public static final String DEFAULT_SENTINEL = "__default__";

    public static final String PREFIX = "tenant_";

    private TenantSchemas() {
    }

    /**
     * Map the CURRENT tenant (from {@link TenantContext}) to the schema identifier Hibernate will
     * pass to the connection provider. Empty context → fail-closed sentinel.
     */
    public static String currentSchema() {
        String tenant = TenantContext.effective();
        if (tenant == null || tenant.isBlank()) {
            return EMPTY_SENTINEL;
        }
        return schemaFor(tenant.trim());
    }

    public static String schemaFor(String tenantId) {
        if (DEFAULT_TENANT.equals(tenantId)) {
            return DEFAULT_SENTINEL;
        }
        return PREFIX + tenantId;
    }
}
