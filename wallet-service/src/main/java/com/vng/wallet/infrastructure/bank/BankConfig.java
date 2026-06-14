package com.vng.wallet.infrastructure.bank;

import com.vng.wallet.domain.BankClient;
import com.vng.wallet.infrastructure.kyc.HmacSigner;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Dây nối {@link BankClient}: mặc định {@link RestBankClient} (RestClient + breaker + HMAC).
 * Khi {@code wallet.bank.mock=true} -> dùng {@link MockBankClient} (@Component conditional) thay thế.
 */
@Configuration
public class BankConfig {

    /** RestBankClient là bean mặc định; tắt khi bật mock (tránh hai bean BankClient). */
    @Bean
    @ConditionalOnProperty(name = "wallet.bank.mock", havingValue = "false", matchIfMissing = true)
    public BankClient bankClient(@Value("${wallet.bank.base-url}") String baseUrl,
                                 @Value("${wallet.bank.hmac-secret}") String secret,
                                 @Value("${wallet.bank.service-id}") String serviceId,
                                 @Value("${wallet.bank.timeout-millis}") int timeoutMillis) {
        CircuitBreaker breaker = CircuitBreaker.of("bank", CircuitBreakerConfig.custom()
                .slidingWindowSize(10).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(2).build());
        return new RestBankClient(baseUrl, secret, serviceId, timeoutMillis, breaker, new HmacSigner());
    }
}
