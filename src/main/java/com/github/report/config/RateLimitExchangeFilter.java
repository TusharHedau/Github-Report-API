package com.github.report.config;

import com.github.report.exception.GithubNotFoundException;
import com.github.report.exception.GithubRateLimitException;
import com.github.report.exception.GithubUnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * WebClient {@link ExchangeFilterFunction} that intercepts GitHub API responses
 * and translates HTTP error status codes into typed domain exceptions.
 *
 * <h2>Why a filter rather than {@code onStatus} in each client call?</h2>
 * <p>Using a filter centralises error-to-exception mapping so every WebClient
 * call in {@code GithubClient} automatically benefits without repeating
 * {@code .onStatus(...)} boilerplate. This follows the DRY principle and ensures
 * consistent error handling across all endpoints.
 *
 * <h2>GitHub rate-limit signals</h2>
 * <p>GitHub uses two different patterns to signal rate limiting:
 * <ul>
 *   <li><b>HTTP 429 Too Many Requests</b> — secondary rate limit (search API,
 *       burst protection). The {@code Retry-After: <seconds>} header tells us
 *       exactly how long to wait.</li>
 *   <li><b>HTTP 403 Forbidden + {@code X-RateLimit-Remaining: 0}</b> — primary
 *       rate limit exhaustion. The {@code X-RateLimit-Reset: <unix-ts>} header
 *       gives the reset time.</li>
 * </ul>
 *
 * <p>Both patterns are mapped to {@link GithubRateLimitException} with the
 * appropriate {@code retryAfter} duration pre-calculated.
 */
@Slf4j
@Component
public class RateLimitExchangeFilter {

    private static final String HEADER_RETRY_AFTER       = "Retry-After";
    private static final String HEADER_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_RATE_LIMIT_RESET  = "X-RateLimit-Reset";

    /**
     * Returns the configured {@link ExchangeFilterFunction}.
     *
     * <p>Invoked by {@link WebClientConfig} when building the {@code WebClient} bean.
     * Returning the filter from a method (rather than making this class itself
     * implement the interface) allows the filter logic to be tested independently
     * as a plain object, and keeps the Spring bean lifecycle concerns separate.
     */
    public ExchangeFilterFunction rateLimitFilter() {
        return ExchangeFilterFunction.ofResponseProcessor(this::handleResponse);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Private dispatch
    // ─────────────────────────────────────────────────────────────────────────────

    private Mono<ClientResponse> handleResponse(ClientResponse response) {
        HttpStatus status = HttpStatus.resolve(response.statusCode().value());
        if (status == null) {
            return Mono.just(response); // Unknown status — pass through
        }

        return switch (status) {
            case TOO_MANY_REQUESTS -> handle429(response);
            case FORBIDDEN         -> handle403(response);
            case UNAUTHORIZED      -> handleUnauthorized(response);
            case NOT_FOUND         -> handleNotFound(response);
            default -> Mono.just(response);
        };
    }

    // ── 429 Too Many Requests ─────────────────────────────────────────────────────

    private Mono<ClientResponse> handle429(ClientResponse response) {
        long retryAfterSeconds = parseRetryAfterHeader(response);
        log.warn("GitHub 429 Too Many Requests — Retry-After: {}s", retryAfterSeconds);
        // Release the response body to prevent connection leaks
        return response.releaseBody()
                .then(Mono.error(
                        GithubRateLimitException.fromRetryAfterHeader(retryAfterSeconds)));
    }

    // ── 403 Forbidden — may or may not be a rate limit ───────────────────────────

    private Mono<ClientResponse> handle403(ClientResponse response) {
        String remaining = response.headers().asHttpHeaders()
                .getFirst(HEADER_RATE_LIMIT_REMAINING);

        // Only treat as a rate-limit error when the quota is confirmed exhausted
        if ("0".equals(remaining)) {
            long resetEpoch = parseRateLimitResetHeader(response);
            long nowEpoch   = Instant.now().getEpochSecond();
            log.warn("GitHub 403 rate limit exhausted — resets at epoch {}, in ~{}s",
                    resetEpoch, Math.max(0, resetEpoch - nowEpoch));
            return response.releaseBody()
                    .then(Mono.error(
                            GithubRateLimitException.fromRateLimitReset(resetEpoch, nowEpoch)));
        }

        // Generic 403 — let the caller handle it (e.g., insufficient PAT scopes)
        log.debug("GitHub 403 Forbidden (non-rate-limit). X-RateLimit-Remaining={}",
                remaining);
        return Mono.just(response);
    }

    // ── 401 Unauthorized ─────────────────────────────────────────────────────────

    private Mono<ClientResponse> handleUnauthorized(ClientResponse response) {
        log.error("GitHub 401 Unauthorized — GITHUB_TOKEN may be invalid or missing");
        return response.releaseBody()
                .then(Mono.error(new GithubUnauthorizedException()));
    }

    // ── 404 Not Found ────────────────────────────────────────────────────────────

    private Mono<ClientResponse> handleNotFound(ClientResponse response) {
        String uri = response.request() != null
                ? response.request().getURI().toString()
                : "unknown";
        log.warn("GitHub 404 Not Found for URI: {}", uri);
        return response.releaseBody()
                .then(Mono.error(new GithubNotFoundException(uri)));
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Header parsing helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Parses the {@code Retry-After} header as seconds.
     * Falls back to a sensible default (60s) if the header is absent or malformed.
     */
    private long parseRetryAfterHeader(ClientResponse response) {
        String raw = response.headers().asHttpHeaders().getFirst(HEADER_RETRY_AFTER);
        if (raw == null) return 60L;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.debug("Could not parse Retry-After header '{}', defaulting to 60s", raw);
            return 60L;
        }
    }

    /**
     * Parses the {@code X-RateLimit-Reset} header as a Unix epoch timestamp.
     * Falls back to now + 60 seconds if absent or malformed.
     */
    private long parseRateLimitResetHeader(ClientResponse response) {
        String raw = response.headers().asHttpHeaders().getFirst(HEADER_RATE_LIMIT_RESET);
        if (raw == null) return Instant.now().getEpochSecond() + 60;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.debug("Could not parse X-RateLimit-Reset header '{}', defaulting to +60s", raw);
            return Instant.now().getEpochSecond() + 60;
        }
    }
}
