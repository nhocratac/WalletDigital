package com.vng.wallet.infrastructure.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Observability Nấc 1 (OB1–OB3) — biên SỚM NHẤT của wallet: continue-or-generate traceId vào MDC.
 *
 * <p>Đọc {@code X-Trace-Id} (gateway forward); thiếu/blank → SINH {@code UUID} (continue-or-generate
 * — mọi entry tự bảo đảm có traceId, không là đặc quyền gateway). Đặt vào {@link MDC} key
 * {@code traceId} → mọi dòng log tự mang {@code [%X{traceId}]} (log pattern application.properties).
 *
 * <p>{@code @Order(Ordered.HIGHEST_PRECEDENCE)} — chạy TRƯỚC {@code HmacVerifyFilter @Order(0)} và
 * {@code TenantFilter @Order(1)} → log của cú 401 (HMAC sai) vẫn mang traceId (OB1).
 *
 * <p>OB3 — clear MDC trong {@code finally}: MDC là ThreadLocal; thread tái dùng (thread-pool) → không
 * clear ⇒ request sau mang traceId của request trước. Echo {@code X-Trace-Id} ra response header.
 *
 * <p>OB7 — traceId là UUID opaque; KHÔNG nhúng tenantId/userId/PII.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        try {
            MDC.put(MDC_KEY, traceId);
            response.setHeader(HEADER, traceId);
            chain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
