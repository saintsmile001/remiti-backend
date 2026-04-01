package com.autoinvoice.config;

import com.autoinvoice.auth.TokenNotFoundException;
import com.autoinvoice.auth.VaultException;
import com.autoinvoice.auth.UnauthorizedException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // Token not connected → 401
    @ExceptionHandler(TokenNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleTokenNotFound(
            TokenNotFoundException ex, HttpServletRequest request) {
        log.warn("Token not found: {}", ex.getMessage());
        return ResponseEntity.status(401).body(buildError(
            401, "Tool not connected", ex.getMessage(), request.getRequestURI()
        ));
    }

    // Session missing/invalid → 401
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Map<String, Object>> handleUnauthorized(
            UnauthorizedException ex, HttpServletRequest request) {
        log.warn("Unauthorized: {}", ex.getMessage());
        return ResponseEntity.status(401).body(buildError(
            401, "Unauthorized", ex.getMessage(), request.getRequestURI()
        ));
    }

    // Auth0 vault error → 502
    @ExceptionHandler(VaultException.class)
    public ResponseEntity<Map<String, Object>> handleVaultException(
            VaultException ex, HttpServletRequest request) {
        log.error("Vault error: {}", ex.getMessage());
        return ResponseEntity.status(502).body(buildError(
            502, "Auth0 Token Vault error", ex.getMessage(), request.getRequestURI()
        ));
    }

    // Not found → 404
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            EntityNotFoundException ex, HttpServletRequest request) {
        return ResponseEntity.status(404).body(buildError(
            404, "Not found", ex.getMessage(), request.getRequestURI()
        ));
    }

    // Bad request → 400
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(
            IllegalStateException ex, HttpServletRequest request) {
        return ResponseEntity.status(400).body(buildError(
            400, "Invalid operation", ex.getMessage(), request.getRequestURI()
        ));
    }

    // Catch-all → 500
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneral(
            Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception at {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return ResponseEntity.status(500).body(buildError(
            500, "Internal server error", ex.getMessage(), request.getRequestURI()
        ));
    }

    private Map<String, Object> buildError(int status, String error,
                                            String message, String path) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("error", error);
        body.put("message", message);
        body.put("path", path);
        body.put("timestamp", LocalDateTime.now().toString());
        return body;
    }
}
