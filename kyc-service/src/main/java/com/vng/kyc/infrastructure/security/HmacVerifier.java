package com.vng.kyc.infrastructure.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Verify HMAC theo HỢP ĐỒNG chung: serviceId\nmethod\npath\ntimestamp\nsha256hex(body). */
@Component
public class HmacVerifier {

    public String buildCanonical(String serviceId, String method, String path,
                                 String timestamp, byte[] body) {
        return String.join("\n", serviceId, method, path, timestamp, sha256Hex(body));
    }

    /** So sánh CONSTANT-TIME — tránh timing attack. */
    public boolean verify(String secret, String canonical, String signatureHex) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            byte[] provided = hexToBytes(signatureHex);
            return MessageDigest.isEqual(expected, provided);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isTimestampFresh(long nowEpochSeconds, long timestamp, long toleranceSeconds) {
        return Math.abs(nowEpochSeconds - timestamp) <= toleranceSeconds;
    }

    private String sha256Hex(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(body == null ? new byte[0] : body)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) return new byte[0];
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
