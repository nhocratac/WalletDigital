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

    private TenantContext() {
    }

    public static void set(String tenantId) {
        CURRENT.set(tenantId);
    }

    public static String get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
