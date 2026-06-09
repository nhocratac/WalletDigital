package com.vng.gateway.infrastructure.routing;

import com.vng.gateway.domain.RouteResolver;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

/**
 * Định tuyến theo prefix path. Downstream path = path gốc bỏ tiền tố "/api".
 * Ví dụ: prefix "/api/wallets" -> base "http://localhost:8080",
 *   "/api/wallets/1/topup" -> base + "/wallets/1/topup".
 */
public class RouteTable implements RouteResolver {

    private static final String API_PREFIX = "/api";

    private final Map<String, String> prefixToBaseUrl;

    public RouteTable(Map<String, String> prefixToBaseUrl) {
        this.prefixToBaseUrl = prefixToBaseUrl;
    }

    @Override
    public Optional<RouteMatch> resolve(String requestPath) {
        return prefixToBaseUrl.entrySet().stream()
                .filter(e -> matchesPrefix(requestPath, e.getKey()))
                .max(Comparator.comparingInt(e -> e.getKey().length()))
                .map(e -> {
                    String prefix = e.getKey();
                    if (!prefix.startsWith(API_PREFIX)) {
                        throw new IllegalStateException(
                                "Route prefix must start with " + API_PREFIX + ": " + prefix);
                    }
                    String downstreamPath = requestPath.substring(API_PREFIX.length());
                    if (downstreamPath.isEmpty()) {
                        downstreamPath = "/";
                    }
                    return new RouteMatch(e.getValue(), downstreamPath);
                });
    }

    private static boolean matchesPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
