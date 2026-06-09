package com.vng.gateway.infrastructure.security;

import com.vng.gateway.domain.AuthenticatedCaller;
import com.vng.gateway.domain.InvalidTokenException;
import com.vng.gateway.domain.TokenVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Verify JWT trước mọi xử lý. 401 nếu thiếu/sai. Chạy sau TraceIdFilter. */
@Component
@Order(2)
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String CALLER_ATTR = "authenticatedCaller";

    private final TokenVerifier tokenVerifier;

    public JwtAuthFilter(TokenVerifier tokenVerifier) {
        this.tokenVerifier = tokenVerifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            write401(response, "Missing Bearer token");
            return;
        }
        try {
            AuthenticatedCaller caller = tokenVerifier.verify(auth.substring("Bearer ".length()));
            request.setAttribute(CALLER_ATTR, caller);
        } catch (InvalidTokenException e) {
            write401(response, e.getMessage());
            return;
        }
        chain.doFilter(request, response);
    }

    private void write401(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
