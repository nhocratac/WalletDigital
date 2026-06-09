package com.vng.gateway.security;

import com.vng.gateway.infrastructure.security.HmacRequestSigner;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

class HmacRequestSignerTest {

    private final HmacRequestSigner signer = new HmacRequestSigner();

    @Test
    void canonical_hasExactContractFormat() {
        // sha256 của body rỗng (hằng số đã biết)
        String emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String expected = String.join("\n",
                "api-gateway",
                "POST",
                "/wallets/1/topup",
                "1749470000",
                emptySha);

        String canonical = signer.buildCanonical("api-gateway", "POST", "/wallets/1/topup",
                "1749470000", new byte[0]);

        assertEquals(expected, canonical, "canonical phải đúng hợp đồng với wallet-service");
    }

    @Test
    void sign_isDeterministicAndMatchesIndependentHmac() throws Exception {
        String secret = "test-secret";
        String canonical = signer.buildCanonical("api-gateway", "GET", "/wallets/1",
                "1749470000", new byte[0]);

        String sig1 = signer.sign(secret, canonical);
        String sig2 = signer.sign(secret, canonical);
        assertEquals(sig1, sig2, "ký phải ổn định (cùng input -> cùng output)");

        // tính độc lập bằng javax.crypto để chắc thuật toán đúng
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : raw) hex.append(String.format("%02x", b));
        assertEquals(hex.toString(), sig1);
    }

    @Test
    void canonical_hashesNonEmptyBody() throws Exception {
        byte[] body = "{\"amount\":50}".getBytes(StandardCharsets.UTF_8);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(body);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format("%02x", b));

        String canonical = signer.buildCanonical("api-gateway", "POST", "/wallets/1/topup",
                "1749470000", body);

        assertTrue(canonical.endsWith(hex.toString()), "dòng cuối canonical = sha256(body)");
    }
}
