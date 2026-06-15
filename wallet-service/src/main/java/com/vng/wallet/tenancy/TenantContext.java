package com.vng.wallet.tenancy;

/**
 * Mang danh tính tenant của request hiện tại trên một ThreadLocal (T3).
 *
 * <p>Cùng họ cơ chế với MDC traceId: set ở biên (TenantFilter / worker loop),
 * đọc ở tầng routing connection, và BẮT BUỘC clear trong finally để thread-pool
 * tái dùng không rò context tenant sang request kế (T4).
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /**
     * Process-wide fallback tenant, used by {@link #effective()} ONLY when no per-thread tenant is
     * set. It is {@code null} in production (so an unset thread stays FAIL-CLOSED — see
     * {@link TenantSchemas#currentSchema()}). It exists for the single-schema baseline: pre-SP5 tests
     * exercise direct service/repository calls (and thread pools / background workers) that never pass
     * through {@link TenantFilter}; a test sets this once so those code paths route to the {@code default}
     * schema across ALL threads. Being static (not ThreadLocal) it also covers spawned threads, which a
     * ThreadLocal default could not. Untouched in production = fail-closed preserved.
     */
    private static volatile String defaultTenant;

    private TenantContext() {
    }

    public static void set(String tenantId) {
        CURRENT.set(tenantId);
    }

    /** The explicit per-thread tenant (set by TenantFilter / worker loop). Null when unset. */
    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * The tenant routing should use: the explicit per-thread value if present, otherwise the
     * process-wide fallback (test-only baseline). Null → caller fails closed.
     */
    public static String effective() {
        String explicit = CURRENT.get();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return defaultTenant;
    }

    /** Test-only baseline: route otherwise-unset threads to this tenant. Null clears the fallback. */
    public static void setDefaultTenant(String tenantId) {
        defaultTenant = tenantId;
    }
}
