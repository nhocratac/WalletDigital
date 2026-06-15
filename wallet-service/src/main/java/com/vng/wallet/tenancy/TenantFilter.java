package com.vng.wallet.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Đọc {@code X-Tenant-Id} (gateway bóc từ claim JWT đã gửi xuống) → set vào
 * {@link TenantContext} → clear trong finally (T3, T4). Chạy SỚM như TraceIdFilter.
 *
 * <p>Thiếu/blank header → 400 và KHÔNG vào chain: request hợp lệ phải qua gateway,
 * gateway luôn gắn header. KHÔNG đọc tenantId từ body/param (D1: không tin client tự khai).
 *
 * <p>FAIL-CLOSED: chặn ngay ở biên — không để request không có tenant đi sâu vào tầng
 * routing rồi mới phát hiện thiếu context.
 *
 * <p>Tin cậy: {@code X-Tenant-Id} hiện CHƯA verify HMAC (wallet Stage 4 debt) — đọc thẳng từ header.
 */
@Component
@Order(1)
public class TenantFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Tenant-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String tenantId = request.getHeader(HEADER);
        if (tenantId == null || tenantId.isBlank()) {
            write400(response, "Missing required header: " + HEADER);
            return;
        }
        try {
            TenantContext.set(tenantId.trim());
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private void write400(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
