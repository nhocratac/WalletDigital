package com.vng.wallet.infrastructure.bank;

import com.vng.wallet.domain.BankClient;
import com.vng.wallet.infrastructure.kyc.HmacSigner;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 4: RestBankClient qua MockWebServer — SETTLED/REJECTED/timeout->UNKNOWN, HMAC ký,
 * breaker mở sau N lỗi (fail-fast, "unknown != failed").
 */
class RestBankClientTest {

    MockWebServer bank;
    RestBankClient client;

    @BeforeEach
    void setUp() throws Exception {
        bank = new MockWebServer();
        bank.start();
        CircuitBreaker breaker = CircuitBreaker.of("bank", CircuitBreakerConfig.custom()
                .slidingWindowSize(4).minimumNumberOfCalls(4)
                .failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(10)).build());
        client = new RestBankClient(bank.url("/").toString().replaceAll("/$", ""),
                "bank-secret", "wallet-service", 1000, breaker, new HmacSigner());
    }

    @AfterEach
    void tearDown() throws Exception { bank.shutdown(); }

    private void enqueueResult(String result) {
        bank.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"bankRef\":\"wd-1\",\"result\":\"" + result + "\"}"));
    }

    @Test
    void transfer_settled_returnsSettledAndSignsHmac() throws Exception {
        enqueueResult("SETTLED");

        BankClient.TransferAck ack = client.transfer("wd-1", new BigDecimal("30"));

        assertEquals(BankClient.BankStatus.SETTLED, ack.result());
        var sent = bank.takeRequest();
        assertNotNull(sent.getHeader("X-Signature"), "phải ký HMAC nội bộ");
        assertEquals("wallet-service", sent.getHeader("X-Service-Id"));
    }

    @Test
    void transfer_rejected_returnsRejected() {
        enqueueResult("REJECTED");
        assertEquals(BankClient.BankStatus.REJECTED, client.transfer("wd-1", new BigDecimal("30")).result());
    }

    @Test
    void transfer_timeout_returnsUnknownNotFailed() {
        bank.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"result\":\"SETTLED\"}")
                .setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS)); // > read-timeout 1s

        assertEquals(BankClient.BankStatus.UNKNOWN, client.transfer("wd-1", new BigDecimal("30")).result(),
                "timeout = UNKNOWN, KHÔNG phải REJECTED (E9)");
    }

    @Test
    void status_settled_returnsSettled() {
        enqueueResult("SETTLED");
        assertEquals(BankClient.BankStatus.SETTLED, client.status("wd-1"));
    }

    @Test
    void serverErrors_openBreaker_thenUnknownWithoutCalling() {
        for (int i = 0; i < 4; i++) bank.enqueue(new MockResponse().setResponseCode(500));
        for (int i = 0; i < 4; i++)
            assertEquals(BankClient.BankStatus.UNKNOWN, client.transfer("wd-1", new BigDecimal("30")).result());
        int callsBefore = bank.getRequestCount();

        assertEquals(BankClient.BankStatus.UNKNOWN, client.transfer("wd-1", new BigDecimal("30")).result());
        assertEquals(callsBefore, bank.getRequestCount(), "breaker mở -> fail-fast, KHÔNG gọi nữa");
    }
}
