package com.vng.wallet.support;

import com.vng.wallet.domain.KycGate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Cho các integration test KHÔNG nhắm vào cổng KYC (ledger, concurrency):
 * thay RestKycGate (trỏ :8082 chết trong test) bằng allow-all @Primary.
 * Hành vi gate thật được test ở RestKycGateTest + WalletKycGateIntegrationTest.
 */
@TestConfiguration
public class AllowAllKycGateTestConfig {

    @Bean
    @Primary
    public KycGate allowAllKycGate() {
        return (userId) -> new KycGate.KycCheckResult(KycGate.Decision.ALLOWED, "APPROVED");
    }
}
