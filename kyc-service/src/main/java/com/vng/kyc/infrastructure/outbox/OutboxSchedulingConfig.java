package com.vng.kyc.infrastructure.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật xử lý {@code @Scheduled} cho {@link OutboxRelay}.
 *
 * <p><b>CỜ RIÊNG, KHÔNG dùng chung {@code kyc.events.kafka-enabled}:</b> nhiều test khác (vd
 * {@code KycServiceOutboxIntegrationTest}) bật {@code kyc.events.kafka-enabled=true} để test đường ghi
 * outbox nhưng lại mock {@code KafkaTemplate} và KHÔNG mong đợi 1 relay thật chạy nền — nếu
 * {@code @EnableScheduling} dùng chung cờ đó, {@link OutboxRelay#relay()} sẽ tự chạy trên thread
 * {@code @Scheduled} trong CHÍNH context của các test đó, gọi {@code KafkaTemplate} đã bị mock (trả
 * null) → NPE, đồng thời âm thầm markSent các outbox row mà test kia đang cần giữ PENDING để assert
 * (đã tái hiện lỗi này khi chạy full suite — outbox-user-1/2 bị relay "trộm" mất trước khi
 * KycServiceOutboxIntegrationTest kịp assert). Do đó dùng {@code kyc.outbox.relay-scheduling-enabled}
 * (mặc định TẮT) — độc lập với {@code kyc.events.kafka-enabled} — giống pattern
 * {@code IdempotencyPurgeSchedulingConfig} bên wallet-service (mỗi worker một cờ bật/tắt riêng).
 * Production bật CẢ HAI cờ; {@link OutboxRelay} bean vẫn tạo khi chỉ {@code kafka-enabled=true} (để
 * test gọi thẳng {@code relayPass()} không cần cờ scheduling).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "kyc.outbox.relay-scheduling-enabled", havingValue = "true")
public class OutboxSchedulingConfig {
}
