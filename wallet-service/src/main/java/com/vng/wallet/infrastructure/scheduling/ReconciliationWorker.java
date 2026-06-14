package com.vng.wallet.infrastructure.scheduling;

import com.vng.wallet.application.ReconciliationService;
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
 */
@Component
@ConditionalOnProperty(name = "wallet.reconcile.enabled", havingValue = "true")
public class ReconciliationWorker {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationWorker.class);

    private final ReconciliationService service;

    public ReconciliationWorker(ReconciliationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${wallet.reconcile.interval-ms:30000}")
    public void run() {
        try {
            service.reconcile();
        } catch (Exception e) {
            // Một vòng lỗi không được giết scheduler — log rồi vòng sau chạy lại.
            log.warn("reconciliation round failed (will retry): {}", e.toString());
        }
    }
}
