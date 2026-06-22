package com.vng.wallet.infrastructure.messaging;

import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WalletTransaction;
import com.vng.wallet.infrastructure.kyc.KycStatusCache;
import com.vng.wallet.support.DefaultTenantContextConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "wallet.events.kafka-enabled=true",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
@EmbeddedKafka(partitions = 3, topics = "kyc.revoked")
@ExtendWith(OutputCaptureExtension.class)
@Import(DefaultTenantContextConfig.class)
class KycRevokedConsumerTest {

    @Autowired KycStatusCache cache;
    @Autowired WalletRepository walletRepository;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;

    private String event(String userId, Instant revokedAt) {
        return "{\"userId\":\"" + userId + "\",\"reason\":\"fraud\",\"revokedAt\":\"" + revokedAt + "\"}";
    }

    @Test
    void revokedEvent_evictsCache_andDuplicateIsHarmless() {
        cache.markApproved("user-evict");
        assertTrue(cache.isApproved("user-evict"));

        kafkaTemplate.send("kyc.revoked", "user-evict", event("user-evict", Instant.now()));
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertFalse(cache.isApproved("user-evict"), "cache phải bị evict"));

        // event TRÙNG -> không lỗi, không chặn consumer (naturally idempotent)
        kafkaTemplate.send("kyc.revoked", "user-evict", event("user-evict", Instant.now()));
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertFalse(cache.isApproved("user-evict")));
    }

    @Test
    void revokedEvent_triggersCompensationScan(CapturedOutput output) {
        Wallet w = walletRepository.save(Wallet.createNew("user-comp", "Eve"));
        Instant revokedAt = Instant.now().minusSeconds(30);
        walletRepository.saveTransaction(new WalletTransaction(null, w.getId(),
                WalletTransaction.Type.WITHDRAW_HOLD, new BigDecimal("99"), "k-sus",
                new BigDecimal("1"), Instant.now()));   // withdraw SAU revokedAt -> nghi vấn

        kafkaTemplate.send("kyc.revoked", "user-comp", event("user-comp", revokedAt));

        // hành vi quan sát được: consumer log COMPENSATION-ALERT với đúng user + giao dịch nghi vấn
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(output.getOut())
                        .contains("COMPENSATION-ALERT")
                        .contains("userId=user-comp")
                        .contains(":99"));   // id:amount của withdraw nghi vấn
    }

    @Test
    void withdrawalBeforeRevokedAt_doesNotTriggerCompensationAlert(CapturedOutput output) {
        Wallet w = walletRepository.save(Wallet.createNew("user-neg", "Mallory"));
        Instant revokedAt = Instant.now();
        walletRepository.saveTransaction(new WalletTransaction(null, w.getId(),
                WalletTransaction.Type.WITHDRAW_HOLD, new BigDecimal("77"), "k-old",
                new BigDecimal("1"), revokedAt.minusSeconds(60)));   // withdraw TRƯỚC revokedAt

        cache.markApproved("user-neg");
        kafkaTemplate.send("kyc.revoked", "user-neg", event("user-neg", revokedAt));

        // evict xảy ra TRƯỚC scan trong consumer -> chờ evict rồi đợi thêm để chắc scan đã chạy xong
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertFalse(cache.isApproved("user-neg")));
        await().pollDelay(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(output.getOut()).doesNotContain("COMPENSATION-ALERT userId=user-neg"));
    }

    @Test
    void recordWithTraceIdHeader_propagatesTraceIdIntoMdcDuringHandling(CapturedOutput output) {
        Wallet w = walletRepository.save(Wallet.createNew("user-trace-in", "Trent"));
        Instant revokedAt = Instant.now().minusSeconds(30);
        walletRepository.saveTransaction(new WalletTransaction(null, w.getId(),
                WalletTransaction.Type.WITHDRAW_HOLD, new BigDecimal("42"), "k-trace",
                new BigDecimal("1"), Instant.now()));   // withdraw SAU revokedAt -> COMPENSATION-ALERT

        ProducerRecord<String, String> rec =
                new ProducerRecord<>("kyc.revoked", "user-trace-in", event("user-trace-in", revokedAt));
        rec.headers().add(new RecordHeader("traceId", "kafka-trace-xyz".getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(rec);

        // COMPENSATION-ALERT log line phải mang traceId từ header (log pattern [%X{traceId}])
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(output.getOut())
                        .contains("[kafka-trace-xyz]")
                        .contains("COMPENSATION-ALERT")
                        .contains("userId=user-trace-in"));
    }

    @Test
    void recordWithoutTraceIdHeader_consumerGeneratesOne_noError(CapturedOutput output) {
        cache.markApproved("user-no-trace");

        // message KHÔNG có header traceId -> consumer phải sinh mới, không lỗi
        kafkaTemplate.send("kyc.revoked", "user-no-trace", event("user-no-trace", Instant.now()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertFalse(cache.isApproved("user-no-trace"), "cache vẫn evict bình thường (không lỗi)"));
        assertThat(output.getOut()).doesNotContain("Cannot process kyc.revoked");
    }
}
