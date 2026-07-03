package com.vng.kyc.infrastructure.outbox;

import com.vng.kyc.infrastructure.observability.TraceIdFilter;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * RELAY (O2, O3, O7) — nửa còn lại của outbox pattern: đọc các row PENDING (ghi bởi
 * {@link OutboxKycEventPublisher} trong cùng tx nghiệp vụ, Task 1/2) và publish thật lên Kafka,
 * rồi mới markSent. Chạy trên thread {@code @Scheduled} riêng (không share tx với đường ghi outbox)
 * — đây chính là điểm "decouple" của outbox pattern (O2).
 *
 * <p><b>Thứ tự (O7):</b> {@code findPending} trả về theo id tăng dần; relay xử lý TUẦN TỰ từng row
 * và CHỜ ack ({@code kafkaTemplate.send(...).get()}) trước khi qua row kế. Nếu 1 row lỗi (send ném,
 * hoặc markSent ném) thì DỪNG NGUYÊN vòng — không nhảy qua row sau — vì nếu tiếp tục, row sau có thể
 * gửi thành công trong khi row trước còn kẹt PENDING, phá vỡ thứ tự toàn cục/theo aggregate mà O7 yêu
 * cầu. Vòng {@code @Scheduled} kế tiếp sẽ retry lại từ đúng row bị kẹt trước.
 *
 * <p><b>At-least-once:</b> send thành công nhưng markSent thất bại (crash giữa 2 bước) thì row VẪN
 * PENDING → vòng sau gửi LẠI. Consumer phía dưới có thể nhận trùng — chấp nhận được (at-least-once,
 * KHÔNG phải exactly-once) miễn consumer tự idempotent theo key/nội dung.
 *
 * <p><b>OB6 (traceId cho worker không có upstream context):</b> mỗi lượt relay sinh 1 root traceId
 * mới vào MDC (giống {@code IdempotencyPurgeWorker} bên wallet-service), gắn traceId đó vào HEADER
 * Kafka {@code traceId} của MỌI record trong lượt đó (không nhét vào payload — OB7), rồi
 * {@code MDC.remove} trong {@code finally} (không đụng MDC key khác nếu có).
 */
@Component
@ConditionalOnProperty(name = "kyc.events.kafka-enabled", havingValue = "true")
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Kafka record header mang traceId (khớp OB5/OB6, cùng tên với KafkaConsumerConfig phía tiêu thụ). */
    public static final String TRACE_ID_HEADER = "traceId";

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int batchSize;

    public OutboxRelay(OutboxRepository outboxRepository,
                        KafkaTemplate<String, String> kafkaTemplate,
                        @Value("${kyc.outbox.relay-batch-size:100}") int batchSize) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
    }

    /**
     * Entry point của scheduler — KHÔNG để 1 lượt lỗi giết thread {@code @Scheduled}.
     * {@code initialDelayString} mặc định 0 (production chạy ngay khi bật {@code kafka-enabled}) —
     * đây là TEST HOOK: {@code fixedDelay} không có initial delay sẽ chạy lượt ĐẦU TIÊN ngay lập tức
     * bất kể interval lớn cỡ nào, nên test nào bật cờ Kafka nhưng cần relay nền im lặng tuyệt đối
     * (mock KafkaTemplate, hoặc gọi thẳng relayPass() cần determinism) phải set
     * {@code kyc.outbox.relay-initial-delay-ms} cực lớn trong {@code @SpringBootTest} properties.
     */
    @Scheduled(fixedDelayString = "${kyc.outbox.relay-interval-ms:2000}",
               initialDelayString = "${kyc.outbox.relay-initial-delay-ms:0}")
    public void relay() {
        try {
            relayPass();
        } catch (Exception e) {
            log.warn("outbox relay pass failed unexpectedly (will retry next round): {}", e.toString());
        }
    }

    /**
     * Một lượt relay — public/directly-invokable để test gọi thẳng, không phụ thuộc scheduler
     * (determinism). Đọc tối đa {@code batchSize} row PENDING (id tăng dần), publish tuần tự, dừng
     * ngay khi 1 row lỗi (xem javadoc lớp).
     */
    public void relayPass() {
        MDC.put(TraceIdFilter.MDC_KEY, UUID.randomUUID().toString());
        try {
            List<OutboxEventEntity> pending = outboxRepository.findPending(batchSize);
            for (OutboxEventEntity event : pending) {
                try {
                    send(event);
                    outboxRepository.markSent(event.getId());
                } catch (Exception e) {
                    // O7: dừng cả lượt tại đây — KHÔNG xử lý row kế để giữ thứ tự toàn cục/aggregate.
                    // Vòng @Scheduled sau sẽ tự retry lại đúng row này (findPending vẫn trả nó là PENDING).
                    log.warn("outbox relay: publish/markSent failed for outbox id={} topic={} aggregate={}"
                                    + " -> stop this pass to preserve ordering (will retry next round): {}",
                            event.getId(), event.getTopic(), event.getAggregate(), e.toString());
                    break;
                }
            }
        } finally {
            MDC.remove(TraceIdFilter.MDC_KEY);
        }
    }

    private void send(OutboxEventEntity event) throws Exception {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.getTopic(), event.getAggregate(), event.getPayload());
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId != null && !traceId.isBlank()) {
            record.headers().add(TRACE_ID_HEADER, traceId.getBytes(StandardCharsets.UTF_8));
        }
        kafkaTemplate.send(record).get(); // chờ ack (acks=all) trước khi markSent — O3.
    }
}
