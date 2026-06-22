package com.vng.wallet.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Stage4 (S1, S3, S5, S7) — biên NỘI BỘ của wallet: verify HMAC inbound TRƯỚC khi TenantFilter
 * dùng {@code X-Tenant-Id} để routing (authenticate-before-use). {@code @Order(0)} đứng trước
 * {@link com.vng.wallet.tenancy.TenantFilter} {@code @Order(1)}.
 *
 * <p>Soi gương kyc {@code InternalAuthFilter}: allowlist {@code X-Service-Id} → secret theo caller →
 * dựng canonical KỲ VỌNG (identity-if-present) từ header request → verify + timestamp freshness.
 *
 * <p>S2 — ràng buộc identity: gateway ký canonical GỒM {@code X-User-Id}/{@code X-Tenant-Id};
 * filter dựng lại canonical từ chính header → đổi {@code X-User-Id} sau khi ký ⇒ canonical lệch ⇒ 401.
 *
 * <p>S4 — webhook bank được MIỄN: {@code /webhooks/**} có verify riêng trong controller
 * ({@code WithdrawalWebhookController}, secret-bank, canonical-không-identity) và {@link
 * com.vng.wallet.tenancy.TenantFilter} cũng skip path này. Filter này KHÔNG xử lý path đó (tránh
 * double-verify + tránh đòi identity mà bank không có).
 *
 * <p>S7 — hợp đồng lỗi: thiếu/sai chữ ký, timestamp cũ, service ngoài allowlist → 401 (không lộ lý do
 * chi tiết cho client; audit-log nội bộ đầy đủ).
 *
 * <p>Gating: {@code wallet.internal.auth-enabled} (mặc định true ở production). Bộ test chức năng cũ
 * tắt cờ này (surefire) để giữ nguyên hành vi; test auth chuyên biệt bật lại + ký request thật.
 */
@Component
@Order(0)
public class HmacVerifyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(HmacVerifyFilter.class);
    private static final long TIMESTAMP_TOLERANCE_SECONDS = 300;
    private static final long MAX_BODY_BYTES = 1_048_576; // 1MB — chặn buffer body chưa xác thực
    private static final String WEBHOOK_PREFIX = "/webhooks/";

    private final HmacVerifier hmac = new HmacVerifier();
    private final boolean enabled;
    private final String internalSecret;
    private final List<String> allowedServices;

    public HmacVerifyFilter(@Value("${wallet.internal.auth-enabled:true}") boolean enabled,
                            @Value("${wallet.internal.hmac-secret:}") String internalSecret,
                            @Value("${wallet.internal.allowed-services:api-gateway}") String allowedServices) {
        this.enabled = enabled;
        this.internalSecret = internalSecret;
        this.allowedServices = Arrays.stream(allowedServices.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * Skip khi tắt (test chức năng) HOẶC path webhook bank (verify riêng trong controller, S4).
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabled) return true;
        String path = request.getRequestURI();
        return path != null && path.startsWith(WEBHOOK_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) { write(response, 413, "Body too large"); return; }
        CachedBodyRequest cached;
        try { cached = new CachedBodyRequest(request, MAX_BODY_BYTES); }
        catch (CachedBodyRequest.BodyTooLargeException e) { write(response, 413, "Body too large"); return; }

        String serviceId = cached.getHeader("X-Service-Id");
        String timestamp = cached.getHeader("X-Timestamp");
        String signature = cached.getHeader("X-Signature");
        String userId = cached.getHeader("X-User-Id");
        String tenantId = cached.getHeader("X-Tenant-Id");

        if (serviceId == null || !allowedServices.contains(serviceId)) {
            audit(cached, "service not allowed: " + serviceId);
            write(response, 401, "Unauthorized"); return;
        }
        if (timestamp == null || signature == null
                || !hmac.isTimestampFresh(Instant.now().getEpochSecond(), parseLong(timestamp), TIMESTAMP_TOLERANCE_SECONDS)) {
            audit(cached, "missing or stale signature/timestamp");
            write(response, 401, "Unauthorized"); return;
        }
        // Canonical identity-if-present: gateway ký GỒM X-User-Id/X-Tenant-Id → dựng lại từ header
        // (đổi header sau khi ký ⇒ canonical lệch ⇒ verify fail).
        String canonical = hmac.buildCanonical(serviceId, cached.getMethod(),
                cached.getRequestURI(), timestamp, cached.getBody(), userId, tenantId);
        if (internalSecret.isEmpty() || !hmac.verify(internalSecret, canonical, signature)) {
            audit(cached, "invalid signature");
            write(response, 401, "Unauthorized"); return;
        }
        chain.doFilter(cached, response);
    }

    /** Audit nội bộ — KHÔNG lộ lý do cho client (S7). */
    private void audit(HttpServletRequest request, String reason) {
        log.warn("inbound HMAC rejected: method={} path={} serviceId={} reason={}",
                request.getMethod(), request.getRequestURI(), request.getHeader("X-Service-Id"), reason);
    }

    private long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    private void write(HttpServletResponse resp, int status, String msg) throws IOException {
        resp.setStatus(status);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.getWriter().write("{\"error\":\"" + msg + "\"}");
    }
}
