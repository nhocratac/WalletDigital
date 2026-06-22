package com.vng.wallet.infrastructure.observability;

import com.vng.wallet.domain.KycGate;
import com.vng.wallet.infrastructure.bank.RestBankClient;
import com.vng.wallet.infrastructure.kyc.HmacSigner;
import com.vng.wallet.infrastructure.kyc.KycStatusCache;
import com.vng.wallet.infrastructure.kyc.RestKycGate;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OB4 — outbound RestClient interceptor đọc MDC traceId → đính {@code X-Trace-Id} vào MỌI request đi
 * ra (wallet → kyc / bank). Chốt: chỉ forward khi MDC CÓ traceId (thiếu → không set header).
 */
class TraceIdClientInterceptorTest {

    static final String HEADER = "X-Trace-Id";

    MockWebServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        MDC.clear();
    }

    @AfterEach
    void tearDown() throws Exception {
        MDC.clear();
        server.shutdown();
    }

    private CircuitBreaker breaker(String name) {
        return CircuitBreaker.of(name, CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(4)
                .failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(10)).build());
    }

    private String baseUrl() {
        return server.url("/").toString().replaceAll("/$", "");
    }

    @Test
    void kycGate_forwardsTraceIdFromMdc() throws Exception {
        MDC.put("traceId", "abc-kyc");
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"userId\":\"u1\",\"status\":\"PENDING\"}"));

        RestKycGate gate = new RestKycGate(baseUrl(), "it-secret", "wallet-service", 2000,
                new KycStatusCache(60), breaker("kyc"), new HmacSigner());
        assertEquals(KycGate.Decision.DENIED, gate.check("u1").decision());

        RecordedRequest sent = server.takeRequest();
        assertEquals("abc-kyc", sent.getHeader(HEADER), "outbound KYC call phải mang X-Trace-Id từ MDC");
    }

    @Test
    void bankClient_forwardsTraceIdFromMdc() throws Exception {
        MDC.put("traceId", "abc-bank");
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"bankRef\":\"r1\",\"result\":\"SETTLED\"}"));

        RestBankClient bank = new RestBankClient(baseUrl(), "it-secret", "wallet-service", 2000,
                breaker("bank"), new HmacSigner());
        bank.transfer("r1", new BigDecimal("10.00"));

        RecordedRequest sent = server.takeRequest();
        assertEquals("abc-bank", sent.getHeader(HEADER), "outbound bank call phải mang X-Trace-Id từ MDC");
    }

    @Test
    void noTraceIdInMdc_doesNotSetHeader() throws Exception {
        // MDC trống (setUp đã clear)
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"userId\":\"u1\",\"status\":\"PENDING\"}"));

        RestKycGate gate = new RestKycGate(baseUrl(), "it-secret", "wallet-service", 2000,
                new KycStatusCache(60), breaker("kyc"), new HmacSigner());
        gate.check("u1");

        RecordedRequest sent = server.takeRequest();
        assertNull(sent.getHeader(HEADER), "MDC trống -> KHÔNG set X-Trace-Id (chỉ forward nếu có)");
    }
}
