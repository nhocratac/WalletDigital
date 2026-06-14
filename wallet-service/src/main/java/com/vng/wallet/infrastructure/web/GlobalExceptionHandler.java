package com.vng.wallet.infrastructure.web;

import com.vng.wallet.domain.IdempotencyKeyConflictException;
import com.vng.wallet.domain.InsufficientFundsException;
import com.vng.wallet.domain.InvalidWithdrawalTransitionException;
import com.vng.wallet.domain.KycNotApprovedException;
import com.vng.wallet.domain.KycUnavailableException;
import com.vng.wallet.domain.WalletNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(WalletNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .findFirst().orElse("validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", msg));
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, String>> insufficient(InsufficientFundsException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY) // 422: tìm thấy nhưng vi phạm quy tắc
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(org.springframework.dao.ConcurrencyFailureException.class)
    public ResponseEntity<Map<String, String>> lockConflict(Exception ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT) // 409: đua nhau cập nhật, retry
                .body(Map.of("error", "Concurrent update, please retry"));
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> duplicateRequest(Exception ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", "Duplicate request, please retry"));
    }

    @ExceptionHandler(InvalidWithdrawalTransitionException.class)
    public ResponseEntity<Map<String, String>> invalidWithdrawalTransition(InvalidWithdrawalTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT) // 409: transition không hợp lệ trên state machine
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<Map<String, String>> idempotencyConflict(IdempotencyKeyConflictException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, String>> missingHeader(org.springframework.web.bind.MissingRequestHeaderException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "Missing required header: " + ex.getHeaderName()));
    }

    @ExceptionHandler(KycNotApprovedException.class)
    public ResponseEntity<Map<String, String>> kycDenied(KycNotApprovedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", ex.getMessage(), "status", String.valueOf(ex.getKycStatus())));
    }

    @ExceptionHandler(KycUnavailableException.class)
    public ResponseEntity<Map<String, String>> kycUnavailable(KycUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "10")                      // header CHUẨN, không có X- (D7)
                .body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
