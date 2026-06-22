package com.vng.wallet.infrastructure.scheduling;

import com.vng.wallet.application.ReconciliationService;
import com.vng.wallet.infrastructure.observability.TraceIdFilter;
import com.vng.wallet.tenancy.TenantContext;
import com.vng.wallet.tenancy.master.TenantRegistry;
import com.vng.wallet.tenancy.master.TenantRegistryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Observability Nấc 1 (OB6) — worker reconciliation chạy trên thread {@code @Scheduled} KHÔNG có
 * request → KHÔNG ai forward {@code X-Trace-Id}. Nó phải tự SINH root traceId (UUID) mỗi vòng/tenant
 * vào MDC để mọi dòng log của vòng đó mang {@code [%X{traceId}]}, rồi remove("traceId") trong finally
 * (KHÔNG đụng TenantContext — đó là ThreadLocal riêng, không phải MDC).
 *
 * <p>Pure unit test (không Spring/DB): hand-rolled JDK proxy cho repository, subclass capture MDC
 * trong reconcile(). Chứng minh: (1) trong lúc reconcile mỗi tenant MDC có traceId non-blank; (2) mỗi
 * tenant một traceId KHÁC (root mới mỗi vòng); (3) sau pass MDC traceId đã remove + TenantContext clear.
 */
class ReconciliationWorkerTraceIdTest {

    @BeforeEach
    @AfterEach
    void clean() {
        MDC.clear();
        TenantContext.clear();
        TenantContext.setDefaultTenant(null);
    }

    /** Capture the MDC traceId observed at the moment reconcile() runs for each tenant. */
    private static final class CapturingReconciliationService extends ReconciliationService {
        final List<String> observedTraceIds = new ArrayList<>();

        CapturingReconciliationService() {
            super(null, null, null, 100);
        }

        @Override
        public void reconcile() {
            observedTraceIds.add(MDC.get(TraceIdFilter.MDC_KEY));
        }
    }

    private TenantRegistryRepository repoReturning(List<TenantRegistry> active) {
        return (TenantRegistryRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{TenantRegistryRepository.class},
                (InvocationHandler) (proxy, method, args) -> {
                    if ("findByStatus".equals(method.getName())) {
                        return active;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt.equals(boolean.class)) return false;
                    if (rt.isPrimitive()) return 0;
                    return null;
                });
    }

    @Test
    void workerPass_generatesFreshRootTraceIdPerTenant_inMdc_thenRemoves() {
        List<TenantRegistry> active = List.of(
                new TenantRegistry("alfa", "tenant_alfa", TenantRegistry.Status.ACTIVE, Instant.now()),
                new TenantRegistry("bravo", "tenant_bravo", TenantRegistry.Status.ACTIVE, Instant.now()));
        CapturingReconciliationService svc = new CapturingReconciliationService();

        new MultiTenantReconciliationRunner(repoReturning(active), svc).runOnce();

        // (1) During each tenant's reconcile, a non-blank traceId was present in MDC.
        assertEquals(2, svc.observedTraceIds.size(), "reconcile ran for both tenants");
        for (String t : svc.observedTraceIds) {
            assertNotNull(t, "traceId present in MDC during reconcile");
            assertFalse(t.isBlank(), "traceId non-blank during reconcile");
        }
        // (2) Fresh root per tenant — the two traceIds differ.
        assertFalse(svc.observedTraceIds.get(0).equals(svc.observedTraceIds.get(1)),
                "each tenant gets a distinct root traceId");
        // (3) After the pass, traceId removed from MDC + tenant context cleared (no leak to pool thread).
        assertNull(MDC.get(TraceIdFilter.MDC_KEY), "traceId removed from MDC after pass");
        assertNull(TenantContext.get(), "tenant context cleared after pass");
    }

    @Test
    void baselineSingleSchema_stillGeneratesRootTraceId_thenRemoves() {
        TenantContext.setDefaultTenant("default");
        CapturingReconciliationService svc = new CapturingReconciliationService();

        new MultiTenantReconciliationRunner(repoReturning(List.of()), svc).runOnce();

        assertEquals(1, svc.observedTraceIds.size(), "baseline reconcile ran once");
        assertNotNull(svc.observedTraceIds.get(0), "traceId present during baseline reconcile");
        assertFalse(svc.observedTraceIds.get(0).isBlank(), "traceId non-blank during baseline reconcile");
        assertNull(MDC.get(TraceIdFilter.MDC_KEY), "traceId removed from MDC after baseline pass");
    }
}
