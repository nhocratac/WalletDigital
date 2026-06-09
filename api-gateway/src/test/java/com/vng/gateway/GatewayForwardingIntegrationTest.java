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
        wallet.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":1}").setHeader("Content-Type", "application/json"));
        String token = keys.signToken("user-1", "acme", 300);

        ResponseEntity<String> resp = rest.exchange(
                "http://localhost:" + gatewayPort + "/api/wallets/1",
                HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);

        assertEquals(200, resp.getStatusCode().value());
        assertEquals(MediaType.APPLICATION_JSON, resp.getHeaders().getContentType());

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

        // X-Signature must be recomputed WITHOUT the production signer so this test
        // locks the wire-level canonical that wallet-service re-verifies.
        // canonical = serviceId \n method \n path \n timestamp \n sha256(body)
        String emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"; // sha256("")
        String canonical = String.join("\n", "api-gateway", "GET", "/wallets/1", fwdTs, emptySha);
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                "it-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(raw.length * 2);
        for (byte b : raw) hex.append(String.format("%02x", b));
        assertEquals(hex.toString(), forwarded.getHeader("X-Signature"),
                "X-Signature must match the canonical wallet-service re-verifies");
    }

    @Test
    void validJwt_forwardsPostBodyIntactAndSignsOverBodyHash() throws Exception {
        wallet.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));
        String token = keys.signToken("user-1", "acme", 300);
        String payload = "{\"amount\":50}";

        HttpHeaders h = authHeaders(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = rest.exchange(
                "http://localhost:" + gatewayPort + "/api/wallets/1/topup",
                HttpMethod.POST, new HttpEntity<>(payload, h), String.class);
        assertEquals(200, resp.getStatusCode().value());

        RecordedRequest forwarded = wallet.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(forwarded);
        assertEquals("POST", forwarded.getMethod());
        assertEquals("/wallets/1/topup", forwarded.getPath());
        assertEquals("acme", forwarded.getHeader("X-Tenant-Id"));

        // (a) body forwarded intact (raw bytes, no re-serialization)
        String forwardedBody = forwarded.getBody().readUtf8();
        assertEquals(payload, forwardedBody);

        // (b) X-Signature recomputed over canonical with sha256(REAL body bytes)
        String fwdTs = forwarded.getHeader("X-Timestamp");
        assertNotNull(fwdTs);
        byte[] bodyBytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder bodySha = new StringBuilder();
        for (byte b : md.digest(bodyBytes)) bodySha.append(String.format("%02x", b));

        String canonical = String.join("\n", "api-gateway", "POST", "/wallets/1/topup", fwdTs, bodySha.toString());
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                "it-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))) hex.append(String.format("%02x", b));
        assertEquals(hex.toString(), forwarded.getHeader("X-Signature"),
                "X-Signature must be computed over sha256 of the actual forwarded body");
    }

    @Test
    void forwardsContentTypeAndAcceptToDownstream_butSignedHeadersStayIntact() throws Exception {
        wallet.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));
        String token = keys.signToken("user-1", "acme", 300);
        String payload = "{\"amount\":50}";

        HttpHeaders h = authHeaders(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<String> resp = rest.exchange(
                "http://localhost:" + gatewayPort + "/api/wallets/1/topup",
                HttpMethod.POST, new HttpEntity<>(payload, h), String.class);
        assertEquals(200, resp.getStatusCode().value());

        RecordedRequest forwarded = wallet.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(forwarded);

        // The bug: Content-Type was dropped -> downstream returned 415. It must be forwarded.
        assertEquals("application/json", forwarded.getHeader("Content-Type"));
        assertEquals("application/json", forwarded.getHeader("Accept"));

        // Signed security headers must still be present and untouched (canonical unchanged).
        assertEquals("acme", forwarded.getHeader("X-Tenant-Id"));
        assertEquals("api-gateway", forwarded.getHeader("X-Service-Id"));
        String fwdTs = forwarded.getHeader("X-Timestamp");
        assertNotNull(fwdTs);

        // Signature is computed over the canonical that does NOT include Content-Type.
        byte[] bodyBytes = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        StringBuilder bodySha = new StringBuilder();
        for (byte b : md.digest(bodyBytes)) bodySha.append(String.format("%02x", b));
        String canonical = String.join("\n", "api-gateway", "POST", "/wallets/1/topup", fwdTs, bodySha.toString());
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                "it-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8))) hex.append(String.format("%02x", b));
        assertEquals(hex.toString(), forwarded.getHeader("X-Signature"),
                "Adding passthrough Content-Type/Accept must NOT change the HMAC canonical/signature");
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

    @Test
    void clientSuppliedSecurityHeaders_cannotOverrideSignedHeaders() throws Exception {
        wallet.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));
        String token = keys.signToken("user-1", "acme", 300);

        HttpHeaders h = authHeaders(token);
        h.add("X-Tenant-Id", "evil-tenant");
        h.add("X-Service-Id", "spoofed");
        h.add("X-Signature", "deadbeef");
        h.add("X-Timestamp", "1");
        h.add("X-Trace-Id", "injected");

        ResponseEntity<String> resp = rest.exchange(
                "http://localhost:" + gatewayPort + "/api/wallets/1",
                HttpMethod.GET, new HttpEntity<>(h), String.class);
        assertEquals(200, resp.getStatusCode().value());

        RecordedRequest fwd = wallet.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(fwd);
        assertEquals("acme", fwd.getHeader("X-Tenant-Id"));        // from JWT, not client
        assertEquals("api-gateway", fwd.getHeader("X-Service-Id")); // gateway identity, not client
        assertNotEquals("injected", fwd.getHeader("X-Trace-Id"));   // trace id is gateway-assigned

        // Strong lock: X-Signature must equal the locally recomputed HMAC over the canonical
        // (NOT the client-supplied "deadbeef"), proving the signed header overwrote passthrough.
        String fwdTs = fwd.getHeader("X-Timestamp");
        assertNotNull(fwdTs);
        assertNotEquals("deadbeef", fwd.getHeader("X-Signature"));
        String emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"; // sha256("")
        String canonical = String.join("\n", "api-gateway", "GET", "/wallets/1", fwdTs, emptySha);
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(
                "it-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            hex.append(String.format("%02x", b));
        assertEquals(hex.toString(), fwd.getHeader("X-Signature"),
                "X-Signature must be the gateway-computed HMAC, never the client value");
    }
}
