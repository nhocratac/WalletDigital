package com.vng.wallet.idempotency;

import com.vng.wallet.infrastructure.observability.TraceIdFilter;
import com.vng.wallet.tenancy.TenantContext;
import com.vng.wallet.tenancy.master.TenantRegistry;
import com.vng.wallet.tenancy.master.TenantRegistryRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Observability Nấc 1 (OB6) — idempotency purge worker chạy trên thread {@code @Scheduled} KHÔNG có
 * request → tự SINH root traceId (UUID) mỗi vòng/tenant vào MDC để log mang {@code [%X{traceId}]}, rồi
 * remove("traceId") trong finally (KHÔNG đụng TenantContext).
 *
 * <p>Pure unit test (không Spring/DB): hand-rolled store capture MDC trong deleteOlderThan, JDK proxy
 * cho repository. Chứng minh root mới mỗi tenant + remove sau pass.
 */
class IdempotencyPurgeWorkerTraceIdTest {

    @BeforeEach
    @AfterEach
    void clean() {
        MDC.clear();
        TenantContext.clear();
        TenantContext.setDefaultTenant(null);
    }

    /** Capture the MDC traceId observed at the moment deleteOlderThan() runs for each tenant. */
    private static final class CapturingStore implements IdempotencyStore {
        final List<String> observedTraceIds = new ArrayList<>();

        @Override public Optional<IdempotencyRecord> find(String idempotencyKey) { return Optional.empty(); }
        @Override public IdempotencyRecord save(IdempotencyRecord record) { return record; }
        @Override public void updateResultRef(String idempotencyKey, String resultRef) { }

        @Override
        public int deleteOlderThan(Instant cutoff) {
            observedTraceIds.add(MDC.get(TraceIdFilter.MDC_KEY));
            return 0;
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
    void purgePass_generatesFreshRootTraceIdPerTenant_inMdc_thenRemoves() {
        List<TenantRegistry> active = List.of(
                new TenantRegistry("alfa", "tenant_alfa", TenantRegistry.Status.ACTIVE, Instant.now()),
                new TenantRegistry("bravo", "tenant_bravo", TenantRegistry.Status.ACTIVE, Instant.now()));
        CapturingStore store = new CapturingStore();

        new IdempotencyPurgeWorker(store, repoReturning(active), 7).runOnce(Instant.now());

        assertEquals(2, store.observedTraceIds.size(), "purge ran for both tenants");
        for (String t : store.observedTraceIds) {
            assertNotNull(t, "traceId present in MDC during purge");
            assertFalse(t.isBlank(), "traceId non-blank during purge");
        }
        assertFalse(store.observedTraceIds.get(0).equals(store.observedTraceIds.get(1)),
                "each tenant gets a distinct root traceId");
        assertNull(MDC.get(TraceIdFilter.MDC_KEY), "traceId removed from MDC after pass");
        assertNull(TenantContext.get(), "tenant context cleared after pass");
    }

    @Test
    void baselineSingleSchema_stillGeneratesRootTraceId_thenRemoves() {
        TenantContext.setDefaultTenant("default");
        CapturingStore store = new CapturingStore();

        new IdempotencyPurgeWorker(store, repoReturning(List.of()), 7).runOnce(Instant.now());

        assertEquals(1, store.observedTraceIds.size(), "baseline purge ran once");
        assertNotNull(store.observedTraceIds.get(0), "traceId present during baseline purge");
        assertFalse(store.observedTraceIds.get(0).isBlank(), "traceId non-blank during baseline purge");
        assertNull(MDC.get(TraceIdFilter.MDC_KEY), "traceId removed from MDC after baseline pass");
    }
}
