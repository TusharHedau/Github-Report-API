package com.github.report.exception;

import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.WriteTimeoutException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ServerWebInputException;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Global Exception Handler for the application using {@code @ControllerAdvice}.
 *
 * <p>Intercepts all exceptions thrown during request processing and maps them
 * to clean, standardized {@link ErrorResponse} bodies.
 *
 * <p>Specifically handles:
 * <ul>
 *   <li>{@link GithubUnauthorizedException} → HTTP 401 Unauthorized</li>
 *   <li>{@link GithubNotFoundException} → HTTP 404 Not Found</li>
 *   <li>{@link GithubRateLimitException} → HTTP 403 / 429 depending on status code</li>
 *   <li>Validation Errors (e.g. invalid organization name path parameters) → HTTP 400 Bad Request</li>
 *   <li>Timeouts (Netty/Reactor timeouts) → HTTP 504 Gateway Timeout</li>
 *   <li>Catch-all → HTTP 500 Internal Server Error</li>
 * </ul>
 */
@Slf4j
@ControllerAdvice
public class GlobalExceptionHandler {

    // ── Custom GitHub Exceptions ──────────────────────────────────────────────────

    @ExceptionHandler(GithubUnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(
            GithubUnauthorizedException ex, ServerHttpRequest request) {
        log.error("Authentication Error [401]: {}", ex.getMessage());
        return buildResponse(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    @ExceptionHandler(GithubNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            GithubNotFoundException ex, ServerHttpRequest request) {
        log.warn("Resource Not Found [404]: {}", ex.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(GithubRateLimitException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(
            GithubRateLimitException ex, ServerHttpRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) status = HttpStatus.TOO_MANY_REQUESTS;

        log.error("GitHub Rate Limit Error [{}]: {}", status.value(), ex.getMessage());
        return buildResponse(status, ex.getMessage(), request);
    }

    // ── Input Validation Exceptions ───────────────────────────────────────────────

    /**
     * Handled when method parameter validation fails (e.g., path variables with @Pattern).
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, ServerHttpRequest request) {
        log.warn("Validation Failure: {}", ex.getMessage());

        Map<String, String> details = new HashMap<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            String propertyPath = violation.getPropertyPath().toString();
            // Simplify property path (e.g., controller method name.parameterName -> parameterName)
            String paramName = propertyPath.substring(propertyPath.lastIndexOf('.') + 1);
            details.put(paramName, violation.getMessage());
        }

        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Invalid input parameters",
                request.getPath().value(),
                details
        );
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Handled in Spring Boot 3 when handler method validation fails.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(
            HandlerMethodValidationException ex, ServerHttpRequest request) {
        log.warn("Validation Failure (Handler Method): {}", ex.getMessage());

        Map<String, String> details = new HashMap<>();
        ex.getAllValidationResults().forEach(result -> {
            String parameterName = result.getMethodParameter().getParameterName();
            result.getResolvableErrors().forEach(error -> 
                details.put(parameterName, error.getDefaultMessage())
            );
        });

        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Invalid input parameters",
                request.getPath().value(),
                details
        );
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Handled when payload body binding/validation fails (e.g., @Valid DTO).
     */
    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorResponse> handleBindException(
            WebExchangeBindException ex, ServerHttpRequest request) {
        log.warn("Payload Bind/Validation Failure: {}", ex.getMessage());

        Map<String, String> details = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            details.put(fieldError.getField(), fieldError.getDefaultMessage());
        }

        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Request body validation failed",
                request.getPath().value(),
                details
        );
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(ServerWebInputException.class)
    public ResponseEntity<ErrorResponse> handleWebInputException(
            ServerWebInputException ex, ServerHttpRequest request) {
        log.warn("Bad Request: {}", ex.getReason());
        return buildResponse(HttpStatus.BAD_REQUEST, ex.getReason(), request);
    }

    // ── Timeout / Gateway Exceptions ──────────────────────────────────────────────

    /**
     * Intercepts connection, read, or write timeouts occurring during GitHub API calls.
     */
    @ExceptionHandler({
            TimeoutException.class,
            ReadTimeoutException.class,
            WriteTimeoutException.class,
            WebClientRequestException.class
    })
    public ResponseEntity<ErrorResponse> handleTimeout(
            Exception ex, ServerHttpRequest request) {
        
        // Log the underlying timeout cause
        log.error("GitHub API Request Timeout: {}", ex.getMessage(), ex);

        String userMessage = "Gateway Timeout. The request to GitHub timed out. Please try again later.";
        return buildResponse(HttpStatus.GATEWAY_TIMEOUT, userMessage, request);
    }

    // ── Catch-All Exception Handler ───────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleCatchAll(
            Exception ex, ServerHttpRequest request) {
        log.error("Internal Server Error occurred at path '{}'", request.getPath(), ex);

        String userMessage = "An unexpected error occurred. Please contact the administrator.";
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, userMessage, request);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helper Methods
    // ─────────────────────────────────────────────────────────────────────────────

    private ResponseEntity<ErrorResponse> buildResponse(
            HttpStatus status, String message, ServerHttpRequest request) {
        ErrorResponse body = new ErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getPath().value()
        );
        return ResponseEntity.status(status).body(body);
    }
}
