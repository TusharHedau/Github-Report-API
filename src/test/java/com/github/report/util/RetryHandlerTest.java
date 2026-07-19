package com.github.report.util;

import com.github.report.config.GithubProperties;
import com.github.report.exception.GithubNotFoundException;
import com.github.report.exception.GithubRateLimitException;
import com.github.report.exception.GithubUnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link RetryHandler}.
 *
 * <p>Uses virtual time ({@link StepVerifier#withVirtualTime}) so retry delays
 * (which can be seconds or minutes) execute instantly without actually waiting.
 */
@DisplayName("RetryHandler")
class RetryHandlerTest {

    private RetryHandler retryHandler;

    @BeforeEach
    void setUp() {
        GithubProperties props = new GithubProperties(
                "https://api.github.com",
                "test-token",
                "2022-11-28",
                100,
                10,
                new GithubProperties.Retry(3, 100L, 5000L),
                new GithubProperties.Timeout(10, 30)
        );
        retryHandler = new RetryHandler(props);
    }

    // ── Non-retryable errors ─────────────────────────────────────────────────────

    @Test
    @DisplayName("does NOT retry GithubUnauthorizedException (401)")
    void noRetry_onUnauthorized() {
        StepVerifier.create(
                Mono.error(new GithubUnauthorizedException())
                        .retryWhen(retryHandler.buildRetrySpec())
        )
        .expectError(GithubUnauthorizedException.class)
        .verify(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("does NOT retry GithubNotFoundException (404)")
    void noRetry_onNotFound() {
        StepVerifier.create(
                Mono.error(new GithubNotFoundException("/orgs/acme"))
                        .retryWhen(retryHandler.buildRetrySpec())
        )
        .expectError(GithubNotFoundException.class)
        .verify(Duration.ofSeconds(1));
    }

    // ── Rate-limit retry ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("retries on GithubRateLimitException and waits the correct duration")
    void retries_onRateLimit_withCorrectDelay() {
        // Use a small retryAfter for the test; real delays would be 60s+
        GithubRateLimitException rateLimitEx =
                GithubRateLimitException.fromRetryAfterHeader(5L);

        // Fail twice with rate-limit, succeed on the 3rd attempt
        final int[] callCount = {0};

        StepVerifier.withVirtualTime(() ->
                Mono.fromCallable(() -> {
                    callCount[0]++;
                    if (callCount[0] <= 2) {
                        throw rateLimitEx;
                    }
                    return "success";
                }).retryWhen(retryHandler.buildRetrySpec())
        )
        // Expect two 5-second waits before success
        .expectSubscription()
        .thenAwait(Duration.ofSeconds(5))   // wait for retry 1
        .thenAwait(Duration.ofSeconds(5))   // wait for retry 2
        .expectNext("success")
        .verifyComplete();

        assertThat(callCount[0]).isEqualTo(3);
    }

    // ── Exponential backoff ──────────────────────────────────────────────────────

    @Test
    @DisplayName("retries transient errors with exponential backoff")
    void retries_transientError_withExponentialBackoff() {
        RuntimeException transientError = new RuntimeException("503 Service Unavailable");

        // Fail twice, succeed on the 3rd attempt
        final int[] callCount = {0};

        StepVerifier.withVirtualTime(() ->
                Mono.fromCallable(() -> {
                    callCount[0]++;
                    if (callCount[0] <= 2) throw transientError;
                    return "ok";
                }).retryWhen(retryHandler.buildRetrySpec())
        )
        // Backoff: attempt 0 → 100ms × 2^0 = 100ms
        //          attempt 1 → 100ms × 2^1 = 200ms
        .expectSubscription()
        .thenAwait(Duration.ofMillis(100))
        .thenAwait(Duration.ofMillis(200))
        .expectNext("ok")
        .verifyComplete();

        assertThat(callCount[0]).isEqualTo(3);
    }

    // ── Max attempts exhaustion ───────────────────────────────────────────────────

    @Test
    @DisplayName("propagates error after max retry attempts are exhausted")
    void propagatesError_afterMaxAttempts() {
        RuntimeException error = new RuntimeException("persistent failure");

        // Always fail — should exhaust 3 attempts and propagate
        StepVerifier.withVirtualTime(() ->
                Mono.error(error)
                        .retryWhen(retryHandler.buildRetrySpec())
        )
        .expectSubscription()
        .thenAwait(Duration.ofSeconds(10)) // fast-forward past all backoffs
        .expectError(RuntimeException.class)
        .verify(Duration.ofSeconds(2));
    }

    // ── Backoff cap ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GithubRateLimitException delay is clamped to MINIMUM_RETRY_DELAY")
    void rateLimitDelay_clampedToMinimum() {
        // Header says 0 seconds → should be clamped to MINIMUM_RETRY_DELAY (5s)
        GithubRateLimitException ex =
                GithubRateLimitException.fromRetryAfterHeader(0L);
        assertThat(ex.getRetryAfter())
                .isEqualTo(GithubRateLimitException.MINIMUM_RETRY_DELAY);
    }

    @Test
    @DisplayName("GithubRateLimitException delay is clamped to MAXIMUM_RETRY_DELAY")
    void rateLimitDelay_clampedToMaximum() {
        // Header says 10 hours → should be clamped to MAXIMUM_RETRY_DELAY (5 min)
        GithubRateLimitException ex =
                GithubRateLimitException.fromRetryAfterHeader(36000L);
        assertThat(ex.getRetryAfter())
                .isEqualTo(GithubRateLimitException.MAXIMUM_RETRY_DELAY);
    }
}
