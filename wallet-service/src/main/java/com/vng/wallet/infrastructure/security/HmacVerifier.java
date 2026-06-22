package com.vng.wallet.infrastructure.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Stage4 (S2): verify HMAC inbound theo canonical "identity-if-present".
 *
 * <p>Soi gương {@code kyc.infrastructure.security.HmacVerifier} (tạm chấp nhận trùng — module
 * shared-hmac dùng chung cần parent POM, đã ghi nợ trong plan/design S6).
 *
 * <p>Canonical CƠ SỞ giống mọi hop nội bộ:
 * <pre>serviceId\nmethod\npath\ntimestamp\nsha256hex(body)</pre>
 * Khi request MANG {@code X-User-Id}/{@code X-Tenant-Id} (hop gateway→wallet), chúng được
 * APPEND theo thứ tự cố định:
 * <pre>...\nsha256hex(body)\nuserId\ntenantId</pre>
 * Khi KHÔNG mang (direct→kyc submit/revoke, webhook bank) → canonical KHÔNG đổi so với hợp
 * đồng cũ → tương thích ngược.
 *
 * <p>An toàn (S2): identity nằm TRONG canonical đã ký → đổi {@code X-User-Id} sau khi ký làm
 * canonical lệch → verify thất bại (401). Identity ràng buộc vào chữ ký.
 *
 * <p>Plain class (KHÔNG {@code @Component}) — {@link HmacVerifyFilter} tự khởi tạo. Tránh phải có
 * bean trong các slice {@code @WebMvcTest} (chỉ quét web component) trong khi filter vẫn được đăng ký.
 */
public class HmacVerifier {

    /** Canonical identity-if-present: append userId/tenantId CHỈ KHI cả hai không null. */
    public String buildCanonical(String serviceId, String method, String path,
                                 String timestamp, byte[] body, String userId, String tenantId) {
        String base = String.join("\n", serviceId, method, path, timestamp, sha256Hex(body));
        if (userId != null && tenantId != null) {
            return base + "\n" + userId + "\n" + tenantId;
        }
        return base;
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
