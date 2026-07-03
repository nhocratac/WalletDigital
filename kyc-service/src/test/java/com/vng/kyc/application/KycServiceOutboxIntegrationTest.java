package com.vng.kyc.application;

import com.vng.kyc.domain.KycDecision;
import com.vng.kyc.domain.KycStatus;
import com.vng.kyc.infrastructure.outbox.OutboxEventEntity;
import com.vng.kyc.infrastructure.outbox.OutboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * O1: khi kyc.events.kafka-enabled=true, revoke() phải ghi outbox row (KHÔNG gọi Kafka trực
 * tiếp) và toàn bộ thay đổi (kyc_case + outbox) phải nguyên tử trong CÙNG transaction.
 */
@SpringBootTest(properties = {
        "kyc.events.kafka-enabled=true",
        // OutboxSchedulingConfig bật @EnableScheduling khi kafka-enabled=true (đúng hợp đồng
        // production). Test này mock KafkaTemplate và KHÔNG mong đợi relay thật chạy nền tự
        // markSent các outbox row đang assert PENDING. LƯU Ý: fixedDelay không có initial delay sẽ
        // fire lượt ĐẦU ngay khi context start bất kể interval — nên phải đẩy CẢ initial-delay
        // (test hook, xem OutboxRelay#relay) lẫn interval ra ngoài thời gian chạy test.
        "kyc.outbox.relay-initial-delay-ms=2147483647",
        "kyc.outbox.relay-interval-ms=2147483647",
        // OutboxPurge (Task 4) chạy nền dưới cùng cờ kafka-enabled — im lặng nó y hệt relay ở trên,
        // nếu không nó có thể xoá SENT row đang được assert giữa lúc test chạy.
        "kyc.outbox.purge-initial-delay-ms=2147483647",
        "kyc.outbox.purge-interval-ms=2147483647"
})
class KycServiceOutboxIntegrationTest {

    @Autowired KycService kycService;
    @Autowired OutboxRepository outboxRepository;

    /** Thay KafkaTemplate thật bằng mock — không cần broker, và cho phép verify KHÔNG bị gọi. */
    @MockitoBean KafkaTemplate<String, String> kafkaTemplate;

    /** Spy để giả lỗi NGAY SAU khi outbox row đã được ghi, vẫn trong cùng tx với revoke(). */
    @MockitoSpyBean OutboxRepository outboxRepositorySpy;

    private String approveNewCase(String userId) {
        String subId = kycService.submit(userId, List.of("doc-1"));
        kycService.applyDecision(subId, KycDecision.Type.APPROVE, "verifier-1", "ok");
        return subId;
    }

    @Test
    void revoke_writesOnePendingOutboxRow_inSameTransactionAsCaseRevoke() {
        String userId = "outbox-user-1";
        approveNewCase(userId);

        kycService.revoke(userId, "compliance-officer", "fraud detected");

        assertEquals(KycStatus.REVOKED, kycService.getStatus(userId));
        List<OutboxEventEntity> matching = outboxRepository.findPending(100).stream()
                .filter(e -> e.getAggregate().equals(userId))
                .toList();
        assertEquals(1, matching.size(), "phải có đúng 1 outbox row PENDING cho user này");
        OutboxEventEntity row = matching.get(0);
        assertEquals("kyc.revoked", row.getTopic());
        assertTrue(row.getPayload().contains("\"userId\":\"" + userId + "\""));
        assertTrue(row.getPayload().contains("\"reason\":\"fraud detected\""));
    }

    @Test
    void revoke_doesNotCallKafkaDirectly() {
        String userId = "outbox-user-2";
        approveNewCase(userId);

        kycService.revoke(userId, "compliance-officer", "fraud detected");

        verify(kafkaTemplate, never()).send(any(org.apache.kafka.clients.producer.ProducerRecord.class));
        verify(kafkaTemplate, never()).send(anyString(), any());
    }

    @Test
    void revoke_whenFailureHappensRightAfterOutboxWrite_rollsBackBothCaseAndOutboxRow() {
        String userId = "outbox-user-3";
        approveNewCase(userId);

        doAnswer(invocation -> {
            OutboxEventEntity saved = (OutboxEventEntity) invocation.callRealMethod();
            assertNotNull(saved.getId(), "outbox row đã được ghi (trong tx) trước khi lỗi xảy ra");
            throw new RuntimeException("simulated failure right after outbox write");
        }).when(outboxRepositorySpy).save(any());

        assertThrows(RuntimeException.class,
                () -> kycService.revoke(userId, "compliance-officer", "fraud detected"));

        assertEquals(KycStatus.APPROVED, kycService.getStatus(userId),
                "case KHÔNG được chuyển REVOKED sau rollback");
        assertTrue(outboxRepository.findPending(100).stream()
                .noneMatch(e -> e.getAggregate().equals(userId)),
                "outbox row KHÔNG được tồn tại sau rollback");
    }
}
