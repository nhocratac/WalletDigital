package com.vng.gateway.security;

import com.vng.gateway.domain.AuthenticatedCaller;
import com.vng.gateway.domain.InvalidTokenException;
import com.vng.gateway.infrastructure.security.JwtTokenVerifier;
import com.vng.gateway.support.RsaTestKeys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenVerifierTest {

    private final RsaTestKeys keys = new RsaTestKeys();
    private final JwtTokenVerifier verifier = new JwtTokenVerifier(keys.publicKey);

    @Test
    void validToken_extractsUserAndTenant() {
        String token = keys.signToken("user-1", "acme", 300);

        AuthenticatedCaller caller = verifier.verify(token);

        assertEquals("user-1", caller.userId());
        assertEquals("acme", caller.tenantId());
    }

    @Test
    void tamperedToken_throws() {
        String token = keys.signToken("user-1", "acme", 300);
        String tampered = token.substring(0, token.length() - 3) + "abc";

        assertThrows(InvalidTokenException.class, () -> verifier.verify(tampered));
    }

    @Test
    void tokenSignedByDifferentKey_throws() {
        RsaTestKeys attacker = new RsaTestKeys();
        String forged = attacker.signToken("user-1", "acme", 300);

        // verifier dùng publicKey của keys, không phải attacker -> phải từ chối
        assertThrows(InvalidTokenException.class, () -> verifier.verify(forged));
    }

    @Test
    void expiredToken_throws() {
        String token = keys.signExpiredToken("user-1", "acme");

        assertThrows(InvalidTokenException.class, () -> verifier.verify(token));
    }
}
