package com.vng.gateway.infrastructure.web;

import com.vng.gateway.application.GatewayService.NoRouteException;
import com.vng.gateway.domain.DownstreamException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GatewayExceptionHandler {

    @ExceptionHandler(NoRouteException.class)
    public ResponseEntity<Map<String, String>> handleNoRoute(NoRouteException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(DownstreamException.class)
    public ResponseEntity<Map<String, String>> handleDownstream(DownstreamException ex) {
        HttpStatus status = ex.getType() == DownstreamException.Type.TIMEOUT
                ? HttpStatus.GATEWAY_TIMEOUT      // 504
                : HttpStatus.BAD_GATEWAY;          // 502
        return ResponseEntity.status(status).body(Map.of("error", ex.getMessage()));
    }
}
