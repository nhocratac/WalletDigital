package com.vng.gateway.support;

import io.jsonwebtoken.Jwts;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;

/** Sinh cặp khoá RSA và ký JWT test bằng PRIVATE key (giả lập IdP). */
public final class RsaTestKeys {

    public final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;

    public RsaTestKeys() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            this.publicKey = (RSAPublicKey) pair.getPublic();
            this.privateKey = (RSAPrivateKey) pair.getPrivate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Token hợp lệ, hết hạn sau `expiresInSeconds`. */
    public String signToken(String userId, String tenantId, long expiresInSeconds) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .claim("tenantId", tenantId)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expiresInSeconds * 1000))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /** Token hợp lệ về chữ ký nhưng thiếu claim tenantId. */
    public String signTokenWithoutTenant(String userId, long expiresInSeconds) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expiresInSeconds * 1000))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /** Token đã hết hạn (exp ở quá khứ). */
    public String signExpiredToken(String userId, String tenantId) {
        long past = System.currentTimeMillis() - 60_000;
        return Jwts.builder()
                .subject(userId)
                .claim("tenantId", tenantId)
                .issuedAt(new Date(past - 1000))
                .expiration(new Date(past))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }
}
