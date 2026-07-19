package com.github.report.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GlobalExceptionHandler}.
 *
 * <p>Since {@link GlobalExceptionHandler} is a standard Spring component, we can invoke its
 * handler methods directly as plain unit tests, utilizing {@link MockServerHttpRequest} to mock
 * the reactive HTTP request details without running a WebFlux server context.
 */
@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("maps GithubUnauthorizedException to 401 Unauthorized")
    void handleUnauthorized_returns401() {
        ServerHttpRequest request = MockServerHttpRequest.get("/api/report/acme").build();
        GithubUnauthorizedException ex = new GithubUnauthorizedException();

        ResponseEntity<ErrorResponse> response = handler.handleUnauthorized(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(401);
        assertThat(response.getBody().error()).isEqualTo("Unauthorized");
        assertThat(response.getBody().message()).contains("GITHUB_TOKEN");
        assertThat(response.getBody().path()).isEqualTo("/api/report/acme");
        assertThat(response.getBody().timestamp()).isNotNull();
    }

    @Test
    @DisplayName("maps GithubNotFoundException to 404 Not Found")
    void handleNotFound_returns404() {
        ServerHttpRequest request = MockServerHttpRequest.get("/api/report/unknown").build();
        GithubNotFoundException ex = new GithubNotFoundException("/orgs/unknown");

        ResponseEntity<ErrorResponse> response = handler.handleNotFound(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(404);
        assertThat(response.getBody().error()).isEqualTo("Not Found");
        assertThat(response.getBody().message()).contains("/orgs/unknown");
        assertThat(response.getBody().path()).isEqualTo("/api/report/unknown");
    }

    @Test
    @DisplayName("maps GithubRateLimitException to status code matching the exception context")
    void handleRateLimit_returnsCorrectStatus() {
        ServerHttpRequest request = MockServerHttpRequest.get("/api/report/acme").build();

        // Test 429 Too Many Requests
        GithubRateLimitException limit429 = GithubRateLimitException.fromRetryAfterHeader(30L);
        ResponseEntity<ErrorResponse> res429 = handler.handleRateLimit(limit429, request);
        assertThat(res429.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(res429.getBody().status()).isEqualTo(429);
        assertThat(res429.getBody().message()).contains("Retry after 30 seconds");

        // Test 403 Forbidden Rate Limit Exhausted
        GithubRateLimitException limit403 = GithubRateLimitException.fromRateLimitReset(1000L, 950L);
        ResponseEntity<ErrorResponse> res403 = handler.handleRateLimit(limit403, request);
        assertThat(res403.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(res403.getBody().status()).isEqualTo(403);
        assertThat(res403.getBody().message()).contains("Reset in 50 seconds");
    }

    @Test
    @DisplayName("maps generic Exceptions to 500 Internal Server Error")
    void handleCatchAll_returns500() {
        ServerHttpRequest request = MockServerHttpRequest.get("/api/report/acme").build();
        RuntimeException ex = new RuntimeException("DB Connection failed");

        ResponseEntity<ErrorResponse> response = handler.handleCatchAll(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(500);
        assertThat(response.getBody().error()).isEqualTo("Internal Server Error");
        assertThat(response.getBody().message()).contains("unexpected error occurred");
    }
}
