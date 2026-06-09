package com.vng.gateway.web;

import com.vng.gateway.domain.DownstreamException;
import com.vng.gateway.infrastructure.web.GatewayExceptionHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayExceptionHandlerTest {

    private final GatewayExceptionHandler handler = new GatewayExceptionHandler();

    @Test
    void timeout_mapsTo504() {
        assertEquals(504, handler.handleDownstream(
                new DownstreamException(DownstreamException.Type.TIMEOUT, "x")).getStatusCode().value());
    }

    @Test
    void upstream5xx_mapsTo502() {
        assertEquals(502, handler.handleDownstream(
                new DownstreamException(DownstreamException.Type.UPSTREAM_5XX, "x")).getStatusCode().value());
    }
}
