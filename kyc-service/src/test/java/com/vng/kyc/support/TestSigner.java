package com.vng.kyc.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Ký request test — cùng canonical hợp đồng. */
public final class TestSigner {

    public static String sign(String secret, String serviceId, String method, String path,
                              String timestamp, byte[] body) {
        try {
            String canonical = String.join("\n", serviceId, method, path, timestamp, sha256Hex(body));
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

    private static String sha256Hex(byte[] body) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest(body == null ? new byte[0] : body)) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
