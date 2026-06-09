package com.vng.gateway.infrastructure.security;

import com.vng.gateway.domain.AuthenticatedCaller;
import com.vng.gateway.domain.InvalidTokenException;
import com.vng.gateway.domain.TokenVerifier;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import java.security.interfaces.RSAPublicKey;

/**
 * ADAPTER cài TokenVerifier bằng RS256: verify chữ ký bằng PUBLIC key.
 * Gateway KHÔNG có private key nên không thể tự tạo token — chỉ kiểm được.
 */
public class JwtTokenVerifier implements TokenVerifier {

    private final RSAPublicKey publicKey;

    public JwtTokenVerifier(RSAPublicKey publicKey) {
        this.publicKey = publicKey;
    }

    @Override
    public AuthenticatedCaller verify(String token) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(publicKey)   // kiểm chữ ký; cũng kiểm exp tự động
                    .build()
                    .parseSignedClaims(token);
            Claims claims = jws.getPayload();
            String userId = claims.getSubject();
            String tenantId = claims.get("tenantId", String.class);
            if (userId == null || tenantId == null) {
                throw new InvalidTokenException("Missing sub or tenantId claim");
            }
            return new AuthenticatedCaller(userId, tenantId);
        } catch (JwtException e) {
            // bao gồm chữ ký sai, hết hạn, malformed
            throw new InvalidTokenException("Invalid JWT: " + e.getMessage());
        }
    }
}
