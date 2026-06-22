package com.vng.gateway.domain;

public interface RequestSigner {
    String buildCanonical(String serviceId, String method, String path, String timestamp, byte[] body);

    /**
     * Stage4 (S2): canonical "identity-if-present" — append {@code userId}/{@code tenantId} CHỈ KHI
     * cả hai không null (thứ tự cố định). Hop gateway→wallet có identity → ký GỒM chúng (downstream
     * verify ràng buộc identity vào chữ ký). Khi null → canonical y hệt bản 5-tham-số (tương thích ngược).
     */
    String buildCanonical(String serviceId, String method, String path, String timestamp,
                          byte[] body, String userId, String tenantId);

    String sign(String secret, String canonical);
}
