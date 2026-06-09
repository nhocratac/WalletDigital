package com.vng.gateway.infrastructure.routing;

import com.vng.gateway.application.GatewayService;
import com.vng.gateway.domain.AuthenticatedCaller;
import com.vng.gateway.domain.DownstreamClient.DownstreamResponse;
import com.vng.gateway.infrastructure.observability.TraceIdFilter;
import com.vng.gateway.infrastructure.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bắt MỌI path/method, lấy caller (do filter đặt), gọi GatewayService. */
@RestController
public class ForwardingController {

    private final GatewayService gatewayService;

    public ForwardingController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> forward(HttpServletRequest request,
                                          @RequestBody(required = false) byte[] body) {
        AuthenticatedCaller caller = (AuthenticatedCaller) request.getAttribute(JwtAuthFilter.CALLER_ATTR);
        String traceId = (String) request.getAttribute(TraceIdFilter.ATTR);

        // Forward only safe content-negotiation headers; security X-* headers are set
        // (and signed) by GatewayService and cannot be overridden by the client.
        Map<String, String> passthrough = new LinkedHashMap<>();
        String contentType = request.getHeader(HttpHeaders.CONTENT_TYPE);
        if (contentType != null && !contentType.isBlank()) {
            passthrough.put(HttpHeaders.CONTENT_TYPE, contentType);
        }
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept != null && !accept.isBlank()) {
            passthrough.put(HttpHeaders.ACCEPT, accept);
        }

        DownstreamResponse resp = gatewayService.route(
                request.getMethod(),
                request.getRequestURI(),
                body,
                caller,
                traceId,
                Instant.now().getEpochSecond(),
                passthrough);

        HttpHeaders out = new HttpHeaders();
        if (resp.headers() != null) {
            resp.headers().forEach((k, v) -> {
                if (k != null && v != null
                        && !k.equalsIgnoreCase("Transfer-Encoding")
                        && !k.equalsIgnoreCase("Connection")
                        && !k.equalsIgnoreCase("Content-Length")) {
                    out.add(k, v);
                }
            });
        }
        return ResponseEntity.status(resp.status()).headers(out).body(resp.body());
    }
}
