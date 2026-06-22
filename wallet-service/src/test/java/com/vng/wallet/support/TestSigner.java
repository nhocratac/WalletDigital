package com.vng.wallet.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Ký request test theo canonical hợp đồng Stage4 "identity-if-present":
 * serviceId\nmethod\npath\nts\nsha256hex(body)[\nuserId\ntenantId].
 * Append identity CHỈ KHI cả userId và tenantId không null (mô phỏng gateway ký gồm identity).
 */
public final class TestSigner {

    /** Ký GỒM identity (mô phỏng hop gateway→wallet). */
    public static String sign(String secret, String serviceId, String method, String path,
                              String timestamp, byte[] body, String userId, String tenantId) {
        return hmac(secret, canonical(serviceId, method, path, timestamp, body, userId, tenantId));
    }

    /** Ký KHÔNG identity (canonical cơ sở). */
    public static String sign(String secret, String serviceId, String method, String path,
                              String timestamp, byte[] body) {
        return sign(secret, serviceId, method, path, timestamp, body, null, null);
    }

    private static String canonical(String serviceId, String method, String path, String timestamp,
                                    byte[] body, String userId, String tenantId) {
        String base = String.join("\n", serviceId, method, path, timestamp, sha256Hex(body));
        if (userId != null && tenantId != null) {
            return base + "\n" + userId + "\n" + tenantId;
        }
        return base;
    }

    private static String hmac(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder sb = new StringBuilder();
            for (byte b : mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)))
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha256Hex(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(body == null ? new byte[0] : body)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private TestSigner() {}
}
