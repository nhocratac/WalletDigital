package com.vng.wallet.infrastructure.scheduling;

import com.vng.wallet.application.ReconciliationService;
import com.vng.wallet.tenancy.master.TenantRegistryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Slow-path scheduler (SP4, E6): gọi {@link ReconciliationService#reconcile()} định kỳ.
 * Logic ở service (test thuần, không phụ thuộc scheduler); worker chỉ là lớp vỏ @Scheduled.
 *
 * <p>Bật bằng {@code wallet.reconcile.enabled=true} (mặc định TẮT để test/dev cũ không tự chạy
 * worker nền). Cùng cặp với webhook fast path (Task 7) + admin (Task 6) — tất cả đi qua cửa
 * nguyên tử {@code applyTerminal} → exactly-once dù đua nhau.
 *
 * <p>SP5 Task 7 (T9): worker chạy trên thread {@code @Scheduled} KHÔNG có request/filter → không
 * nhận tenant từ header. Nó uỷ quyền cho {@link MultiTenantReconciliationRunner} để lặp tenant
 * registry + set/clear {@link com.vng.wallet.tenancy.TenantContext} cho từng tenant (quét order
 * trong ĐÚNG schema từng tenant), clear trong finally (T4), cô lập lỗi từng tenant.
 */
@Component
@ConditionalOnProperty(name = "wallet.reconcile.enabled", havingValue = "true")
public class ReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationWorker.class);

    private final MultiTenantReconciliationRunner runner;

    public ReconciliationWorker(ReconciliationService service,
                                TenantRegistryRepository registryRepository) {
        this.runner = new MultiTenantReconciliationRunner(registryRepository, service);
    }

    @Scheduled(fixedDelayString = "${wallet.reconcile.interval-ms:30000}")
    public void run() {
        try {
            runner.runOnce();
        } catch (Exception e) {
            // Một vòng lỗi không được giết scheduler — log rồi vòng sau chạy lại.
            log.warn("reconciliation round failed (will retry): {}", e.toString());
        }
    }
}
