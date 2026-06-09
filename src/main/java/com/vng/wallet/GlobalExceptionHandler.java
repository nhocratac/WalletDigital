package com.vng.wallet;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * One central place to turn exceptions into tidy HTTP responses.
 *
 * Without this, asking for a missing wallet would return an ugly 500 error
 * with a stack trace. Instead, we return a clean 404 with a helpful message.
 *
 * ARCHITECT NOTE: centralizing error handling means every endpoint behaves
 * consistently. Clients always get the same error shape. This is a small thing
 * that makes an API feel professional and predictable.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(WalletNotFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND) // HTTP 404
                .body(Map.of("error", ex.getMessage()));
    }
}
