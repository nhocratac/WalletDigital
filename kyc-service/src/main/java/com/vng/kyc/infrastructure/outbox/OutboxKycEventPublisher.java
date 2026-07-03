package com.vng.kyc.infrastructure.outbox;

import com.vng.kyc.domain.KycEventPublisher;
import com.vng.kyc.infrastructure.observability.TraceIdFilter;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * ADAPTER outbox (O1) — publisher trên đường revoke khi bật cờ Kafka.
 * publishKycRevoked() KHÔNG gọi Kafka trực tiếp: chỉ ghi 1 row PENDING vào bảng outbox,
 * trong CÙNG transaction với thay đổi nghiệp vụ (KycService.revoke() là @Transactional) —
 * nếu tx rollback thì row outbox cũng biến mất cùng lúc (nguyên tử). Một relay riêng
 * (OutboxRelay, Task 3) sẽ đọc PENDING và thật sự publish lên Kafka.
 *
 * <p><b>traceId correlation (post-review fix):</b> trước khi có outbox, traceId của chính request
 * HTTP revoke (đặt vào MDC bởi {@code TraceIdFilter}) được gắn thẳng vào header Kafka record —
 * gateway/kyc/wallet correlate trên CÙNG 1 id (OB5). Outbox tách publish ra khỏi request gốc nên
 * phải LƯU traceId đó vào row NGAY tại thời điểm ghi (còn trong ngữ cảnh HTTP/MDC) để relay — chạy
 * trên thread nền khác, không còn MDC của request — có thể khôi phục lại đúng id gốc thay vì tự
 * sinh 1 root mới không liên quan.
 */
@Component
@ConditionalOnProperty(name = "kyc.events.kafka-enabled", havingValue = "true")
public class OutboxKycEventPublisher implements KycEventPublisher {

    public static final String TOPIC = "kyc.revoked";

    private final OutboxRepository outboxRepository;

    public OutboxKycEventPublisher(OutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    @Override
    public void publishKycRevoked(String userId, String reason) {
        String payload = "{\"userId\":\"" + userId + "\",\"reason\":\"" + reason.replace("\"", "'")
                + "\",\"revokedAt\":\"" + Instant.now() + "\"}";
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        outboxRepository.save(new OutboxEventEntity(
                userId, TOPIC, payload, OutboxStatus.PENDING, Instant.now(), null, traceId));
    }
}
