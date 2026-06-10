package com.vng.kyc.infrastructure.security;

import com.vng.kyc.infrastructure.config.KycProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Biên NỘI BỘ: mọi path TRỪ /kyc/webhooks/**. Verify HMAC nội bộ + allowlist.
 * Riêng /kyc/cases/{u}/revoke yêu cầu thêm role compliance trong X-Roles (AuthZ).
 */
@Component
@Order(1)
public class InternalAuthFilter extends OncePerRequestFilter {

    private final HmacVerifier hmac;
    private final KycProperties props;

    public InternalAuthFilter(HmacVerifier hmac, KycProperties props) {
        this.hmac = hmac;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/kyc/webhooks/"); // biên webhook có filter riêng
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long max = props.getMaxBodyBytes();
        if (request.getContentLengthLong() > max) { write(response, 413, "Body too large"); return; }
        CachedBodyRequest cached;
        try { cached = new CachedBodyRequest(request, max); }
        catch (CachedBodyRequest.BodyTooLargeException e) { write(response, 413, "Body too large"); return; }
        String serviceId = cached.getHeader("X-Service-Id");
        String timestamp = cached.getHeader("X-Timestamp");
        String signature = cached.getHeader("X-Signature");

        if (serviceId == null || !props.getAllowedServices().contains(serviceId)) {
            write(response, 403, "Service not allowed"); return;
        }
        if (timestamp == null || signature == null
                || !hmac.isTimestampFresh(Instant.now().getEpochSecond(), parseLong(timestamp), 300)) {
            write(response, 401, "Missing or stale signature"); return;
        }
        String canonical = hmac.buildCanonical(serviceId, cached.getMethod(),
                cached.getRequestURI(), timestamp, cached.getBody());
        if (!hmac.verify(props.getInternalHmacSecret(), canonical, signature)) {
            write(response, 401, "Invalid signature"); return;
        }
        // AuthZ: revoke cần role compliance (gateway bóc roles từ JWT, gắn X-Roles)
        if (cached.getRequestURI().endsWith("/revoke")) {
            String roles = cached.getHeader("X-Roles");
            if (roles == null || !java.util.Arrays.asList(roles.split(",")).contains(props.getRevokeRole())) {
                write(response, 403, "Missing role: " + props.getRevokeRole()); return;
            }
        }
        chain.doFilter(cached, response);
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
