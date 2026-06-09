package com.vng.gateway.application;

import com.vng.gateway.domain.AuthenticatedCaller;
import com.vng.gateway.domain.DownstreamClient;
import com.vng.gateway.domain.DownstreamClient.DownstreamRequest;
import com.vng.gateway.domain.DownstreamClient.DownstreamResponse;
import com.vng.gateway.domain.GatewayIdentity;
import com.vng.gateway.domain.RequestSigner;
import com.vng.gateway.domain.RouteResolver;
import com.vng.gateway.domain.RouteResolver.RouteMatch;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Điều phối luồng gateway sau khi JWT đã được verify (filter làm trước):
 * resolve route -> dựng header đã ký -> forward.
 * Ném NoRouteException nếu không khớp route (controller map -> 404).
 */
public class GatewayService {

    public static class NoRouteException extends RuntimeException {
        public NoRouteException(String path) { super("No route for " + path); }
    }

    private final RouteResolver routeTable;
    private final RequestSigner signer;
    private final DownstreamClient downstreamClient;
    private final GatewayIdentity identity;

    public GatewayService(RouteResolver routeTable, RequestSigner signer,
                          DownstreamClient downstreamClient, GatewayIdentity identity) {
        this.routeTable = routeTable;
        this.signer = signer;
        this.downstreamClient = downstreamClient;
        this.identity = identity;
    }

    public DownstreamResponse route(String method, String requestPath, byte[] body,
                                    AuthenticatedCaller caller, String traceId, long epochSeconds) {
        Optional<RouteMatch> match = routeTable.resolve(requestPath);
        if (match.isEmpty()) {
            throw new NoRouteException(requestPath);
        }
        RouteMatch route = match.get();

        String timestamp = Long.toString(epochSeconds);
        String canonical = signer.buildCanonical(identity.serviceId(), method,
                route.downstreamPath(), timestamp, body == null ? new byte[0] : body);
        String signature = signer.sign(identity.hmacSecret(), canonical);

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Service-Id", identity.serviceId());
        headers.put("X-Timestamp", timestamp);
        headers.put("X-Signature", signature);
        headers.put("X-Tenant-Id", caller.tenantId());   // bóc từ JWT, KHÔNG từ client
        headers.put("X-Trace-Id", traceId);

        return downstreamClient.forward(
                new DownstreamRequest(method, route.baseUrl(), route.downstreamPath(), body, headers));
    }
}
