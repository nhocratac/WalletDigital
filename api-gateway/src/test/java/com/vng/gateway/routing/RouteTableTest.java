package com.vng.gateway.routing;

import com.vng.gateway.domain.RouteResolver;
import com.vng.gateway.infrastructure.routing.RouteTable;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RouteTableTest {

    // prefix "/api/wallets" -> base "http://localhost:8080"
    private final RouteTable table = new RouteTable(java.util.Map.of("/api/wallets", "http://localhost:8080"));

    @Test
    void matchingPath_resolvesBaseUrlAndStripsApiPrefix() {
        Optional<RouteResolver.RouteMatch> match = table.resolve("/api/wallets/1/topup");

        assertTrue(match.isPresent());
        assertEquals("http://localhost:8080", match.get().baseUrl());
        assertEquals("/wallets/1/topup", match.get().downstreamPath());
    }

    @Test
    void unknownPath_resolvesEmpty() {
        assertTrue(table.resolve("/api/orders/9").isEmpty());
    }

    @Test
    void exactApiMount_resolvesRootDownstreamPath() {
        RouteTable rootTable = new RouteTable(java.util.Map.of("/api", "http://localhost:8080"));

        Optional<RouteResolver.RouteMatch> match = rootTable.resolve("/api");

        assertTrue(match.isPresent());
        assertEquals("/", match.get().downstreamPath());
    }

    @Test
    void nonApiPrefix_throwsIllegalState() {
        RouteTable badTable = new RouteTable(java.util.Map.of("/internal/wallets", "http://localhost:8080"));

        assertThrows(IllegalStateException.class, () -> badTable.resolve("/internal/wallets/1"));
    }
}
