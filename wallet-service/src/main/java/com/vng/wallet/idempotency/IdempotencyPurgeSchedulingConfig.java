package com.vng.wallet.idempotency;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật xử lý {@code @Scheduled} cho {@link IdempotencyPurgeWorker} khi
 * {@code wallet.idempotency.purge.enabled=true}. Tách khỏi reconciliation's SchedulingConfig để purge
 * bật/tắt độc lập (hai worker khác cadence). {@code @EnableScheduling} idempotent khi nhiều config bật.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "wallet.idempotency.purge.enabled", havingValue = "true")
public class IdempotencyPurgeSchedulingConfig {
}
