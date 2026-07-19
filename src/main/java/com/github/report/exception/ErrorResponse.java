package com.github.report.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

/**
 * Standardized structure for all error responses returned by the API.
 *
 * <p>Ensures that client applications receive consistent, actionable error payloads:
 * <pre>
 * {
 *   "timestamp": "2026-07-18T22:00:00Z",
 *   "status": 404,
 *   "error": "Not Found",
 *   "message": "GitHub resource not found: /orgs/unknown-org/repos",
 *   "path": "/api/report/unknown-org"
 * }
 * </pre>
 *
 * @param timestamp the instant the error occurred
 * @param status    the HTTP status code integer
 * @param error     the short HTTP status description (e.g. "Not Found")
 * @param message   the user-friendly explanation of the error
 * @param path      the request URI path that caused the error
 * @param details   optional detailed field validation errors (excluded if null)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Standard API error response payload")
public record ErrorResponse(
        @Schema(description = "Timestamp when the error occurred", example = "2026-07-18T22:00:00Z")
        Instant timestamp,

        @Schema(description = "HTTP Status code integer", example = "404")
        int status,

        @Schema(description = "Short HTTP error phrase description", example = "Not Found")
        String error,

        @Schema(description = "Detailed user-friendly error message description", example = "GitHub resource not found: /orgs/invalid-org/repos")
        String message,

        @Schema(description = "The request path URI where the error was thrown", example = "/api/report/invalid-org")
        String path,

        @Schema(description = "Optional detailed parameter/field level validation violations mapping")
        Map<String, String> details
) {
    public ErrorResponse(int status, String error, String message, String path) {
        this(Instant.now(), status, error, message, path, null);
    }

    public ErrorResponse(int status, String error, String message, String path, Map<String, String> details) {
        this(Instant.now(), status, error, message, path, details);
    }
}
