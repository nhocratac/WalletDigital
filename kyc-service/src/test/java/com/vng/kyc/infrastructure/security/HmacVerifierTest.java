package com.vng.kyc.infrastructure.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HmacVerifierTest {

    private final HmacVerifier verifier = new HmacVerifier();

    @Test
    void canonical_matchesGatewayContract() {
        String emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String expected = String.join("\n",
                "api-gateway", "POST", "/kyc/submissions", "1749470000", emptySha);

        assertEquals(expected, verifier.buildCanonical(
                "api-gateway", "POST", "/kyc/submissions", "1749470000", new byte[0]));
    }

    @Test
    void verify_acceptsCorrectSignature_rejectsWrong() throws Exception {
        String secret = "s3cret";
        String canonical = verifier.buildCanonical("api-gateway", "GET", "/kyc/cases/u/status", "1749470000", new byte[0]);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)))
            hex.append(String.format("%02x", b));

        assertTrue(verifier.verify(secret, canonical, hex.toString()));
        assertFalse(verifier.verify(secret, canonical, "deadbeef"));
        assertFalse(verifier.verify("wrong-secret", canonical, hex.toString()));
    }

    @Test
    void timestampWithinTolerance_check() {
        long now = 1749470000L;
        assertTrue(verifier.isTimestampFresh(now, now - 200, 300));
        assertFalse(verifier.isTimestampFresh(now, now - 400, 300)); // quá 5 phút -> replay
    }
}
