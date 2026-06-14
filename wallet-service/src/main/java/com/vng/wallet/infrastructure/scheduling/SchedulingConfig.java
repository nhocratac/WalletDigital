package com.vng.wallet.infrastructure.scheduling;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật xử lý {@code @Scheduled} chỉ khi {@code wallet.reconcile.enabled=true} — đồng bộ với
 * {@link ReconciliationWorker}. Test/dev cũ (không bật) sẽ không khởi động thread pool nền.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "wallet.reconcile.enabled", havingValue = "true")
public class SchedulingConfig {
}
