package com.vng.wallet.infrastructure.kyc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Ký HMAC theo canonical chung: serviceId\nmethod\npath\ntimestamp\nsha256hex(body)
 *  (lần lặp thứ 3 của canonical này -> nợ shared-hmac đã ghi). */
public class HmacSigner {
    public String sign(String secret, String serviceId, String method, String path,
                       String timestamp, byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder bodyHex = new StringBuilder();
            for (byte b : md.digest(body == null ? new byte[0] : body)) bodyHex.append(String.format("%02x", b));
            String canonical = String.join("\n", serviceId, method, path, timestamp, bodyHex.toString());
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder sig = new StringBuilder();
            for (byte b : mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))) sig.append(String.format("%02x", b));
            return sig.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }
}
