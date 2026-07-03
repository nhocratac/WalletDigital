package com.vng.kyc.infrastructure.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Bật xử lý {@code @Scheduled} cho {@link OutboxRelay}.
 *
 * <p><b>Cùng cờ với {@code kyc.events.kafka-enabled}, KHÔNG cờ riêng:</b> theo hợp đồng activation
 * của outbox pattern, relay PHẢI chạy nền bất cứ khi nào {@code kyc.events.kafka-enabled=true} — đây
 * là cấu hình production thực tế (chỉ set {@code KYC_KAFKA_ENABLED=true}), không có cờ thứ hai nào
 * khác được set. Nếu gate bằng 1 cờ riêng mặc định TẮT, outbox row được ghi PENDING nhưng relay không
 * bao giờ chạy trong production thật — row tích luỹ PENDING vĩnh viễn, lỗi âm thầm.
 *
 * <p><b>Test nào cần tránh relay chạy nền thật (vd {@code KycServiceOutboxIntegrationTest} — mock
 * {@code KafkaTemplate}, không mong đợi relay tự markSent các row đang assert PENDING):</b> set CẢ
 * {@code kyc.outbox.relay-initial-delay-ms} LẪN {@code kyc.outbox.relay-interval-ms} thành giá trị
 * cực lớn (vd {@code Integer.MAX_VALUE}) trong {@code @SpringBootTest(properties = ...)} của chính
 * test đó — {@code fixedDelay} KHÔNG có initial delay sẽ fire lượt đầu NGAY khi context start bất kể
 * interval, nên chỉ interval là chưa đủ (xem {@link OutboxRelay#relay()}). Không cần cờ riêng ở
 * production code.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "kyc.events.kafka-enabled", havingValue = "true")
public class OutboxSchedulingConfig {
}
