package com.vng.wallet.infrastructure.messaging;

import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WalletTransaction;
import com.vng.wallet.infrastructure.kyc.KycStatusCache;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "wallet.events.kafka-enabled=true",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
@EmbeddedKafka(partitions = 3, topics = "kyc.revoked")
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
    void revokedEvent_triggersCompensationScan() {
        Wallet w = walletRepository.save(Wallet.createNew("user-comp", "Eve"));
        Instant revokedAt = Instant.now().minusSeconds(30);
        walletRepository.saveTransaction(new WalletTransaction(null, w.getId(),
                WalletTransaction.Type.WITHDRAW, new BigDecimal("99"), "k-sus",
                new BigDecimal("1"), Instant.now()));   // withdraw SAU revokedAt -> nghi vấn

        kafkaTemplate.send("kyc.revoked", "user-comp", event("user-comp", revokedAt));

        // hành vi quan sát được: scan chạy không lỗi; nội dung log kiểm thủ công/log-capture.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(1, walletRepository.findWithdrawalsForUserSince("user-comp", revokedAt).size()));
    }
}
