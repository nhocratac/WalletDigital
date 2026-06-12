package com.vng.wallet.infrastructure.kyc;

import com.vng.wallet.domain.KycGate;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class KycGateConfig {

    @Bean
    public KycStatusCache kycStatusCache(@Value("${wallet.kyc.cache-ttl-seconds}") long ttl) {
        return new KycStatusCache(ttl);
    }

    @Bean
    public KycGate kycGate(@Value("${wallet.kyc.base-url}") String baseUrl,
                           @Value("${wallet.kyc.hmac-secret}") String secret,
                           @Value("${wallet.kyc.service-id}") String serviceId,
                           @Value("${wallet.kyc.timeout-millis}") int timeoutMillis,
                           KycStatusCache cache) {
        CircuitBreaker breaker = CircuitBreaker.of("kyc", CircuitBreakerConfig.custom()
                .slidingWindowSize(10).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(2).build());
        return new RestKycGate(baseUrl, secret, serviceId, timeoutMillis, cache, breaker, new HmacSigner());
    }
}
