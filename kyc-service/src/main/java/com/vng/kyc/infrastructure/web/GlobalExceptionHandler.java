package com.vng.kyc.infrastructure.web;

import com.vng.kyc.domain.InvalidKycTransitionException;
import com.vng.kyc.domain.KycCaseNotFoundException;
import com.vng.kyc.domain.SubmissionNotFoundException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidKycTransitionException.class)
    public ResponseEntity<Map<String, String>> invalidTransition(InvalidKycTransitionException ex) {
        return body(HttpStatus.CONFLICT, ex.getMessage()); // 409: input đúng, trạng thái không cho phép
    }

    @ExceptionHandler(SubmissionNotFoundException.class)
    public ResponseEntity<Map<String, String>> submissionNotFound(SubmissionNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(KycCaseNotFoundException.class)
    public ResponseEntity<Map<String, String>> caseNotFound(KycCaseNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadableBody(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        // Thông điệp chung chung có chủ đích — không lộ enum constants/class names ra ngoài.
        return body(HttpStatus.BAD_REQUEST, "Malformed request body");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> lockConflict(OptimisticLockingFailureException ex) {
        return body(HttpStatus.CONFLICT, "Concurrent update, please retry");
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, String>> integrityConflict(
            org.springframework.dao.DataIntegrityViolationException ex) {
        // Lưới an toàn cho race duplicate-PK/UNIQUE — thông điệp chung, không lộ schema.
        return body(HttpStatus.CONFLICT, "Concurrent update, please retry");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldError() != null
                ? ex.getBindingResult().getFieldError().getDefaultMessage() : "Invalid request";
        return body(HttpStatus.BAD_REQUEST, msg);
    }

    private ResponseEntity<Map<String, String>> body(HttpStatus status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }
}
