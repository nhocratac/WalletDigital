package com.vng.gateway;

import com.vng.gateway.support.RsaTestKeys;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.http.ResponseEntity;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayTimeoutIntegrationTest {

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
        reg.add("gateway.read-timeout", () -> "200ms");
        reg.add("gateway.routes.[/api/wallets]", () -> "http://localhost:" + wallet.getPort());
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    @Test
    void downstreamTimeout_mapsTo504() {
        wallet.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        String token = keys.signToken("user-1", "acme", 300);

        ResponseEntity<String> resp = rest.exchange(
                "http://localhost:" + gatewayPort + "/api/wallets/1",
                HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);

        assertEquals(504, resp.getStatusCode().value());
    }
}
