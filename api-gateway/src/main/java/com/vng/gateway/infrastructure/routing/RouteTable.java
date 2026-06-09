package com.vng.gateway.infrastructure.routing;

import java.util.Map;
import java.util.Optional;

/**
 * Định tuyến theo prefix path. Downstream path = path gốc bỏ tiền tố "/api".
 * Ví dụ: prefix "/api/wallets" -> base "http://localhost:8080",
 *   "/api/wallets/1/topup" -> base + "/wallets/1/topup".
 */
public class RouteTable {

    public record RouteMatch(String baseUrl, String downstreamPath) {}

    private static final String API_PREFIX = "/api";

    private final Map<String, String> prefixToBaseUrl;

    public RouteTable(Map<String, String> prefixToBaseUrl) {
        this.prefixToBaseUrl = prefixToBaseUrl;
    }

    public Optional<RouteMatch> resolve(String requestPath) {
        for (Map.Entry<String, String> e : prefixToBaseUrl.entrySet()) {
            if (requestPath.startsWith(e.getKey())) {
                String downstreamPath = requestPath.substring(API_PREFIX.length());
                return Optional.of(new RouteMatch(e.getValue(), downstreamPath));
            }
        }
        return Optional.empty();
    }
}
