package com.vng.wallet.infrastructure.security;

import com.vng.wallet.support.TestSigner;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Stage4 Task 1: canonical identity-if-present + verify + timestamp freshness. */
class HmacVerifierTest {

    private final HmacVerifier verifier = new HmacVerifier();

    @Test
    void canonical_withoutIdentity_isUnchangedBaseContract() {
        String emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String expected = String.join("\n", "api-gateway", "POST", "/wallets/1/topup", "1749470000", emptySha);

        String canonical = verifier.buildCanonical("api-gateway", "POST", "/wallets/1/topup",
                "1749470000", new byte[0], null, null);

        assertEquals(expected, canonical, "null identity -> canonical co so KHONG doi (tuong thich nguoc)");
    }

    @Test
    void canonical_withIdentity_appendsUserAndTenant() {
        String base = verifier.buildCanonical("api-gateway", "POST", "/wallets/1/topup",
                "1749470000", new byte[0], null, null);
        String withId = verifier.buildCanonical("api-gateway", "POST", "/wallets/1/topup",
                "1749470000", new byte[0], "user-1", "acme");

        assertEquals(base + "\nuser-1\nacme", withId, "co identity -> append userId\\ntenantId");
    }

    @Test
    void canonical_partialIdentity_doesNotAppend() {
        String base = verifier.buildCanonical("api-gateway", "GET", "/wallets/1",
                "1749470000", new byte[0], null, null);
        assertEquals(base, verifier.buildCanonical("api-gateway", "GET", "/wallets/1",
                "1749470000", new byte[0], "user-1", null), "thieu tenantId -> khong append");
        assertEquals(base, verifier.buildCanonical("api-gateway", "GET", "/wallets/1",
                "1749470000", new byte[0], null, "acme"), "thieu userId -> khong append");
    }

    @Test
    void verify_acceptsValidSignature_rejectsTampered() {
        byte[] body = "{\"amount\":50}".getBytes(StandardCharsets.UTF_8);
        String canonical = verifier.buildCanonical("api-gateway", "POST", "/wallets/1/topup",
                "1749470000", body, "user-1", "acme");
        String sig = TestSigner.sign("secret", "api-gateway", "POST", "/wallets/1/topup",
                "1749470000", body, "user-1", "acme");

        assertTrue(verifier.verify("secret", canonical, sig), "chu ky dung -> qua");
        assertFalse(verifier.verify("wrong-secret", canonical, sig), "sai secret -> fail");
        assertFalse(verifier.verify("secret", canonical, "deadbeef"), "sai chu ky -> fail");
    }

    @Test
    void verify_isBoundToIdentity() {
        byte[] body = new byte[0];
        // ky cho user-1 nhung verify canonical cua user-EVIL -> fail (identity rang buoc vao chu ky)
        String sigForVictim = TestSigner.sign("secret", "api-gateway", "POST", "/wallets/9/withdraw",
                "1749470000", body, "user-1", "acme");
        String canonicalForge = verifier.buildCanonical("api-gateway", "POST", "/wallets/9/withdraw",
                "1749470000", body, "user-EVIL", "acme");

        assertFalse(verifier.verify("secret", canonicalForge, sigForVictim),
                "doi userId sau khi ky -> canonical lech -> verify fail");
    }

    @Test
    void timestampFreshness() {
        assertTrue(verifier.isTimestampFresh(1000, 1000, 300));
        assertTrue(verifier.isTimestampFresh(1000, 1300, 300));
        assertFalse(verifier.isTimestampFresh(1000, 1301, 300), "qua cu/qua tuong lai -> fail");
    }
}
