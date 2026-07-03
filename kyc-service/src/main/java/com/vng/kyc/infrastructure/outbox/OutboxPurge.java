package com.vng.kyc.infrastructure.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * PURGE (O5) — dọn các row SENT đã cũ hơn TTL để bảng outbox không phình vô hạn (row SENT vẫn giữ
 * làm audit/at-least-once trail trong 1 khoảng thời gian, sau đó không còn giá trị).
 *
 * <p>Cùng cờ activation với {@link OutboxRelay} ({@code kyc.events.kafka-enabled=true}) — production
 * chỉ set 1 biến môi trường, không có cờ thứ hai. Không xoá row PENDING dù cũ đến đâu — chỉ SENT mới
 * bị purge (xem {@link OutboxRepository#deleteSentOlderThan(Instant)}).
 *
 * <p><b>Test hook:</b> giống {@link OutboxRelay#relay()}, {@code fixedDelay} không có initial delay
 * sẽ fire lượt ĐẦU ngay khi context start bất kể interval — test nào bật kafka-enabled nhưng cần
 * purge nền im lặng phải override CẢ {@code kyc.outbox.purge-initial-delay-ms} LẪN
 * {@code kyc.outbox.purge-interval-ms} thành giá trị cực lớn trong {@code @SpringBootTest} properties.
 */
@Component
@ConditionalOnProperty(name = "kyc.events.kafka-enabled", havingValue = "true")
public class OutboxPurge {

    private static final Logger log = LoggerFactory.getLogger(OutboxPurge.class);

    private final OutboxRepository outboxRepository;
    private final long ttlDays;

    public OutboxPurge(OutboxRepository outboxRepository,
                        @Value("${kyc.outbox.ttl-days:7}") long ttlDays) {
        this.outboxRepository = outboxRepository;
        this.ttlDays = ttlDays;
    }

    /**
     * Entry point của scheduler — KHÔNG để 1 lượt lỗi giết thread {@code @Scheduled}.
     */
    @Scheduled(fixedDelayString = "${kyc.outbox.purge-interval-ms:3600000}",
               initialDelayString = "${kyc.outbox.purge-initial-delay-ms:0}")
    public void purge() {
        try {
            purgeOldSentEvents();
        } catch (Exception e) {
            log.warn("outbox purge pass failed unexpectedly (will retry next round): {}", e.toString());
        }
    }

    /**
     * Một lượt purge — public/directly-invokable để test gọi thẳng, không phụ thuộc scheduler
     * (determinism). Xoá mọi row SENT có {@code sentAt} cũ hơn {@code now - ttlDays}.
     */
    public void purgeOldSentEvents() {
        Instant cutoff = Instant.now().minus(ttlDays, ChronoUnit.DAYS);
        outboxRepository.deleteSentOlderThan(cutoff);
    }
}
