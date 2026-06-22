package com.vng.gateway.application;

import com.vng.gateway.domain.AuthenticatedCaller;
import com.vng.gateway.domain.DownstreamClient;
import com.vng.gateway.domain.DownstreamClient.DownstreamRequest;
import com.vng.gateway.domain.DownstreamClient.DownstreamResponse;
import com.vng.gateway.domain.GatewayIdentity;
import com.vng.gateway.domain.RequestSigner;
import com.vng.gateway.domain.RouteResolver;
import com.vng.gateway.domain.RouteResolver.RouteMatch;
import com.vng.gateway.infrastructure.security.HmacRequestSigner;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the security invariant at the GatewayService entry point: client-supplied
 * passthrough headers (7th arg of route(...)) can NEVER override the gateway's signed
 * X-* headers. Exercises GatewayService.java:66-77 directly with a malicious map.
 */
class GatewayServiceTest {

    // Real signer (the HMAC canonical is a contract; do not re-implement it here).
    private final RequestSigner signer = new HmacRequestSigner();

    // Test double: resolves any path to a fixed downstream match (mirrors RouteTableTest).
    private final RouteResolver routeTable =
            requestPath -> Optional.of(new RouteMatch("http://localhost:9999", "/wallets/1"));

    @Test
    void signedSecurityHeaders_overrideCollidingPassthroughKeys() {
        AtomicReference<DownstreamRequest> captured = new AtomicReference<>();
        DownstreamClient capturing = req -> {
            captured.set(req);
            return new DownstreamResponse(200, new byte[0], Map.of());
        };

        GatewayService svc = new GatewayService(routeTable, signer, capturing,
                new GatewayIdentity("api-gateway", "it-secret"));

        Map<String, String> malicious = Map.of(
                "X-Tenant-Id", "evil",
                "X-Signature", "deadbeef",
                "X-Service-Id", "spoofed");

        svc.route("GET", "/api/wallets/1", new byte[0],
                new AuthenticatedCaller("user-1", "acme"),
                "trace-xyz", 1700000000L, malicious);   // 7th arg = passthroughHeaders

        Map<String, String> sent = captured.get().headers();
        assertEquals("acme", sent.get("X-Tenant-Id"));        // signed value wins over "evil"
        assertEquals("api-gateway", sent.get("X-Service-Id")); // signed value wins over "spoofed"
        assertNotEquals("deadbeef", sent.get("X-Signature"));  // signed value wins over client value

        // The signed X-Signature must be exactly the signer output over the real canonical.
        // Stage4 (S2): canonical now BINDS identity (X-User-Id/X-Tenant-Id) — the gateway signs the
        // 7-arg identity-if-present canonical, so the expected signature covers caller.userId/tenantId.
        String canonical = signer.buildCanonical("api-gateway", "GET", "/wallets/1",
                "1700000000", new byte[0], "user-1", "acme");
        assertEquals(signer.sign("it-secret", canonical), sent.get("X-Signature"));
    }
}
