package com.vng.gateway.routing;

import com.vng.gateway.infrastructure.routing.RouteTable;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RouteTableTest {

    // prefix "/api/wallets" -> base "http://localhost:8080"
    private final RouteTable table = new RouteTable(java.util.Map.of("/api/wallets", "http://localhost:8080"));

    @Test
    void matchingPath_resolvesBaseUrlAndStripsApiPrefix() {
        Optional<RouteTable.RouteMatch> match = table.resolve("/api/wallets/1/topup");

        assertTrue(match.isPresent());
        assertEquals("http://localhost:8080", match.get().baseUrl());
        assertEquals("/wallets/1/topup", match.get().downstreamPath());
    }

    @Test
    void unknownPath_resolvesEmpty() {
        assertTrue(table.resolve("/api/orders/9").isEmpty());
    }
}
