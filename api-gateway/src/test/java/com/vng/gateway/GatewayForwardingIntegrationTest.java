package com.vng.gateway;

import com.vng.gateway.support.RsaTestKeys;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayForwardingIntegrationTest {

    static RsaTestKeys keys = new RsaTestKeys();
    static MockWebServer wallet;

    @LocalServerPort
    int gatewayPort;

    @Autowired
    TestRestTemplate rest;

    @BeforeAll
    static void startMock() throws Exception {
        wallet = new MockWebServer();
        wallet.start();
    }

    @AfterAll
    static void stopMock() throws Exception {
        wallet.shutdown();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("gateway.jwt-public-key",
                () -> Base64.getEncoder().encodeToString(keys.publicKey.getEncoded()));
        reg.add("gateway.hmac-secret", () -> "it-secret");
        reg.add("gateway.routes.[/api/wallets]", () -> "http://localhost:" + wallet.getPort());
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    @Test
    void validJwt_forwardsSignedRequestWithTenantFromToken() throws Exception {
        wallet.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":1}"));
        String token = keys.signToken("user-1", "acme", 300);

        ResponseEntity<String> resp = rest.exchange(
                "http://localhost:" + gatewayPort + "/api/wallets/1",
                HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);

        assertEquals(200, resp.getStatusCode().value());

        RecordedRequest forwarded = wallet.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(forwarded);
        assertEquals("/wallets/1", forwarded.getPath());
        assertEquals("acme", forwarded.getHeader("X-Tenant-Id"));   // bóc từ JWT
        assertEquals("api-gateway", forwarded.getHeader("X-Service-Id"));
        String fwdTs = forwarded.getHeader("X-Timestamp");
        assertNotNull(fwdTs);
        long ts = Long.parseLong(fwdTs);                       // must be parseable epoch seconds
        assertTrue(Math.abs(java.time.Instant.now().getEpochSecond() - ts) < 120,
                "X-Timestamp must be ~now");

        // Recompute the signature independently via the SAME production signer to lock the
        // end-to-end contract: serviceId="api-gateway", method="GET", downstreamPath="/wallets/1",
        // empty body, secret="it-secret". wallet-service re-verifies with this exact canonical.
        com.vng.gateway.infrastructure.security.HmacRequestSigner signer =
                new com.vng.gateway.infrastructure.security.HmacRequestSigner();
        String canonical = signer.buildCanonical("api-gateway", "GET", "/wallets/1", fwdTs, new byte[0]);
        String expectedSig = signer.sign("it-secret", canonical);
        assertEquals(expectedSig, forwarded.getHeader("X-Signature"),
                "X-Signature must match canonical the gateway signs, so wallet-service can re-verify");
    }

    @Test
    void missingJwt_returns401AndDoesNotForward() {
        int before = wallet.getRequestCount();

        ResponseEntity<String> resp = rest.getForEntity(
                "http://localhost:" + gatewayPort + "/api/wallets/1", String.class);

        assertEquals(401, resp.getStatusCode().value());
        // 401 phải CHẶN trước khi forward: số request xuống wallet KHÔNG tăng.
        // (count của MockWebServer là tích luỹ trên instance static dùng chung,
        //  nên kiểm "không tăng" thay vì "bằng 0" để độc lập thứ tự chạy test.)
        assertEquals(before, wallet.getRequestCount());
    }

    @Test
    void downstream5xx_mapsTo502() {
        wallet.enqueue(new MockResponse().setResponseCode(500));
        String token = keys.signToken("user-1", "acme", 300);

        ResponseEntity<String> resp = rest.exchange(
                "http://localhost:" + gatewayPort + "/api/wallets/1",
                HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);

        assertEquals(502, resp.getStatusCode().value());
    }

    @Test
    void unknownRoute_returns404() {
        String token = keys.signToken("user-1", "acme", 300);

        ResponseEntity<String> resp = rest.exchange(
                "http://localhost:" + gatewayPort + "/api/orders/9",
                HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);

        assertEquals(404, resp.getStatusCode().value());
    }
}
