package com.vng.gateway.application;

import com.vng.gateway.domain.AuthenticatedCaller;
import com.vng.gateway.domain.DownstreamClient;
import com.vng.gateway.domain.DownstreamClient.DownstreamRequest;
import com.vng.gateway.domain.DownstreamClient.DownstreamResponse;
import com.vng.gateway.infrastructure.config.GatewayProperties;
import com.vng.gateway.infrastructure.routing.RouteTable;
import com.vng.gateway.infrastructure.routing.RouteTable.RouteMatch;
import com.vng.gateway.infrastructure.security.HmacRequestSigner;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Điều phối luồng gateway sau khi JWT đã được verify (filter làm trước):
 * resolve route -> dựng header đã ký -> forward.
 * Ném NoRouteException nếu không khớp route (controller map -> 404).
 */
@Service
public class GatewayService {

    public static class NoRouteException extends RuntimeException {
        public NoRouteException(String path) { super("No route for " + path); }
    }

    private final RouteTable routeTable;
    private final HmacRequestSigner signer;
    private final DownstreamClient downstreamClient;
    private final GatewayProperties props;

    public GatewayService(RouteTable routeTable, HmacRequestSigner signer,
                          DownstreamClient downstreamClient, GatewayProperties props) {
        this.routeTable = routeTable;
        this.signer = signer;
        this.downstreamClient = downstreamClient;
        this.props = props;
    }

    public DownstreamResponse route(String method, String requestPath, byte[] body,
                                    AuthenticatedCaller caller, String traceId, long epochSeconds) {
        Optional<RouteMatch> match = routeTable.resolve(requestPath);
        if (match.isEmpty()) {
            throw new NoRouteException(requestPath);
        }
        RouteMatch route = match.get();

        String timestamp = Long.toString(epochSeconds);
        String canonical = signer.buildCanonical(props.getServiceId(), method,
                route.downstreamPath(), timestamp, body == null ? new byte[0] : body);
        String signature = signer.sign(props.getHmacSecret(), canonical);

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Service-Id", props.getServiceId());
        headers.put("X-Timestamp", timestamp);
        headers.put("X-Signature", signature);
        headers.put("X-Tenant-Id", caller.tenantId());   // bóc từ JWT, KHÔNG từ client
        headers.put("X-Trace-Id", traceId);

        return downstreamClient.forward(
                new DownstreamRequest(method, route.baseUrl(), route.downstreamPath(), body, headers));
    }
}
