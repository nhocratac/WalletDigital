package com.vng.gateway.domain;

import java.util.Optional;

public interface RouteResolver {
    record RouteMatch(String baseUrl, String downstreamPath) {}
    Optional<RouteMatch> resolve(String requestPath);
}
