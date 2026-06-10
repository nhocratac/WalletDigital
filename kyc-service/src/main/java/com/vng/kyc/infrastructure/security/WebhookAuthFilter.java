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
 * Biên WEBHOOK (verifier NGOÀI hệ): chỉ /kyc/webhooks/**.
 * Secret RIÊNG (segmentation) — lộ secret verifier không lan vào nội bộ.
 * Canonical dùng serviceId cố định "verifier".
 */
@Component
@Order(1)
public class WebhookAuthFilter extends OncePerRequestFilter {

    private final HmacVerifier hmac;
    private final KycProperties props;

    public WebhookAuthFilter(HmacVerifier hmac, KycProperties props) {
        this.hmac = hmac;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/kyc/webhooks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long max = props.getMaxBodyBytes();
        if (request.getContentLengthLong() > max) { write(response, 413, "Body too large"); return; }
        CachedBodyRequest cached;
        try { cached = new CachedBodyRequest(request, max); }
        catch (CachedBodyRequest.BodyTooLargeException e) { write(response, 413, "Body too large"); return; }
        String timestamp = cached.getHeader("X-Timestamp");
        String signature = cached.getHeader("X-Signature");
        if (timestamp == null || signature == null
                || !hmac.isTimestampFresh(Instant.now().getEpochSecond(), parseLong(timestamp), 300)) {
            write(response, 401, "Missing or stale signature"); return;
        }
        String canonical = hmac.buildCanonical("verifier", cached.getMethod(),
                cached.getRequestURI(), timestamp, cached.getBody());
        if (!hmac.verify(props.getVerifierHmacSecret(), canonical, signature)) {
            write(response, 401, "Invalid signature"); return;
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
