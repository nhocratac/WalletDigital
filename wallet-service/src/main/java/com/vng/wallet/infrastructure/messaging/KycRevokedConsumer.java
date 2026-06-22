package com.vng.wallet.infrastructure.messaging;

import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WalletTransaction;
import com.vng.wallet.infrastructure.kyc.KycStatusCache;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Nghe kyc.revoked: (1) evict cache — naturally idempotent, KHÔNG dedup (D9);
 * (2) compensation scan ledger (D5) — scope SP3: phát hiện + log alert.
 * groupId có suffix ngẫu nhiên: broadcast khi scale nhiều instance (bẫy multi-instance, design §5.1).
 */
@Component
@ConditionalOnProperty(name = "wallet.events.kafka-enabled", havingValue = "true")
public class KycRevokedConsumer {

    private static final Logger log = LoggerFactory.getLogger(KycRevokedConsumer.class);
    /** Kafka record header carrying the correlation ID (OB5). Matches the producer side. */
    private static final String TRACE_ID_HEADER = "traceId";
    private static final String MDC_TRACE_KEY = "traceId";

    private final KycStatusCache cache;
    private final WalletRepository walletRepository;

    public KycRevokedConsumer(KycStatusCache cache, WalletRepository walletRepository) {
        this.cache = cache;
        this.walletRepository = walletRepository;
    }

    @KafkaListener(topics = "kyc.revoked",
            groupId = "wallet-kyc-revoked-#{T(java.util.UUID).randomUUID().toString()}")
    public void onKycRevoked(ConsumerRecord<String, String> record) {
        // continue-or-generate: đọc traceId từ header; thiếu -> sinh root mới (OB5). Thread tái dùng -> clear finally (OB3).
        MDC.put(MDC_TRACE_KEY, resolveTraceId(record));
        String payload = record.value();
        try {
            String userId = extract(payload, "userId");
            Instant revokedAt = Instant.parse(extract(payload, "revokedAt"));

            cache.evict(userId);   // chặn rút TIẾP THEO ngay; xoá 2 lần vô hại (D9)

            List<WalletTransaction> suspicious =
                    walletRepository.findWithdrawalsForUserSince(userId, revokedAt);
            if (!suspicious.isEmpty()) {
                log.warn("COMPENSATION-ALERT userId={} revokedAt={} suspiciousWithdrawals={}",
                        userId, revokedAt,
                        suspicious.stream().map(t -> t.id() + ":" + t.amount()).toList());
            }
        } catch (Exception e) {
            // poison message: log + bỏ qua, không chặn partition (DLT = YAGNI đã ghi)
            log.error("Cannot process kyc.revoked payload: {}", payload, e);
        } finally {
            MDC.remove(MDC_TRACE_KEY);   // ThreadLocal: không rò trace sang message sau (thread tái dùng)
        }
    }

    private String resolveTraceId(ConsumerRecord<String, String> record) {
        Header h = record.headers().lastHeader(TRACE_ID_HEADER);
        if (h != null && h.value() != null) {
            String traceId = new String(h.value(), StandardCharsets.UTF_8);
            if (!traceId.isBlank()) {
                return traceId;
            }
        }
        return UUID.randomUUID().toString();
    }

    private String extract(String json, String field) {
        return json.replaceAll(".*\"" + field + "\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }
}
