package com.vng.gateway.infrastructure.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Dựng chuỗi canonical + ký HMAC-SHA256. Định dạng canonical là HỢP ĐỒNG
 * phải khớp y hệt wallet-service.HmacVerifier:
 *   serviceId \n method \n path \n timestamp \n sha256(body)
 */
@Component
public class HmacRequestSigner {

    public String buildCanonical(String serviceId, String method, String path,
                                 String timestamp, byte[] body) {
        return String.join("\n", serviceId, method, path, timestamp, sha256Hex(body));
    }

    public String sign(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    private String sha256Hex(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(body));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 failed", e);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
