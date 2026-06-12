package com.vng.wallet.infrastructure.kyc;

import com.vng.wallet.domain.KycGate;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class RestKycGateTest {

    MockWebServer kyc;
    RestKycGate gate;
    KycStatusCache cache;

    @BeforeEach
    void setUp() throws Exception {
        kyc = new MockWebServer();
        kyc.start();
        cache = new KycStatusCache(60);
        CircuitBreaker breaker = CircuitBreaker.of("kyc", CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(4)
                .failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(10)).build());
        gate = new RestKycGate(kyc.url("/").toString().replaceAll("/$", ""),
                "it-secret", "wallet-service", 2000, cache, breaker, new HmacSigner());
    }

    @AfterEach
    void tearDown() throws Exception { kyc.shutdown(); }

    private void enqueueStatus(String status) {
        kyc.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"userId\":\"u1\",\"status\":\"" + status + "\"}"));
    }

    @Test
    void approved_allowsAndCaches() throws Exception {
        enqueueStatus("APPROVED");

        var r1 = gate.check("u1");
        assertEquals(KycGate.Decision.ALLOWED, r1.decision());
        var sent = kyc.takeRequest();
        assertEquals("/kyc/cases/u1/status", sent.getPath());
        assertNotNull(sent.getHeader("X-Signature"), "phải ký HMAC nội bộ");
        assertEquals("wallet-service", sent.getHeader("X-Service-Id"));

        var r2 = gate.check("u1");   // lần 2: cache hit, KHÔNG network
        assertEquals(KycGate.Decision.ALLOWED, r2.decision());
        assertEquals(1, kyc.getRequestCount(), "cache hit -> không gọi lại");
    }

    @Test
    void pending_deniesAndDoesNotCache() {
        enqueueStatus("PENDING");
        var r = gate.check("u2");
        assertEquals(KycGate.Decision.DENIED, r.decision());
        assertEquals("PENDING", r.kycStatus());

        enqueueStatus("APPROVED");   // lần 2 PHẢI gọi lại (không cache trạng thái âm — D6)
        assertEquals(KycGate.Decision.ALLOWED, gate.check("u2").decision());
        assertEquals(2, kyc.getRequestCount());
    }

    @Test
    void serverErrors_openBreaker_thenUnavailableWithoutCalling() {
        for (int i = 0; i < 4; i++) kyc.enqueue(new MockResponse().setResponseCode(500));
        for (int i = 0; i < 4; i++) assertEquals(KycGate.Decision.UNAVAILABLE, gate.check("u3").decision());
        int callsBefore = kyc.getRequestCount();

        assertEquals(KycGate.Decision.UNAVAILABLE, gate.check("u3").decision()); // breaker MỞ
        assertEquals(callsBefore, kyc.getRequestCount(), "breaker mở -> fail-fast, KHÔNG gọi nữa");
    }

    @Test
    void breakerOpen_butCachedApproved_stillAllows() {
        enqueueStatus("APPROVED");
        gate.check("u4");                                       // ghi cache
        for (int i = 0; i < 4; i++) kyc.enqueue(new MockResponse().setResponseCode(500));
        for (int i = 0; i < 4; i++) gate.check("u-other-" + i); // mở breaker bằng user khác

        assertEquals(KycGate.Decision.ALLOWED, gate.check("u4").decision(), "cache thoát hiểm khi breaker mở");
    }
}
