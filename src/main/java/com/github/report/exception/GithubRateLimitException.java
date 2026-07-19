package com.github.report.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.time.Duration;

/**
 * Thrown when GitHub responds with HTTP 429 (Too Many Requests) or HTTP 403
 * with {@code X-RateLimit-Remaining: 0}, indicating the API rate limit has
 * been exhausted.
 *
 * <p>Carries a {@link #retryAfter} duration parsed from either:
 * <ul>
 *   <li>{@code Retry-After: <seconds>} header (present on 429 responses)</li>
 *   <li>{@code X-RateLimit-Reset: <unix-timestamp>} header (present on 403 rate-limit
 *       responses), converted to a duration relative to now</li>
 * </ul>
 *
 * <p>The retry handler uses this duration to schedule the next attempt instead
 * of calculating a generic exponential delay, thereby respecting GitHub's own
 * reset window.
 */
public class GithubRateLimitException extends GithubApiException {

    /** Minimum delay enforced even if the header says 0 or is absent. */
    public static final Duration MINIMUM_RETRY_DELAY = Duration.ofSeconds(5);

    /** Maximum delay cap — avoids waiting longer than 5 minutes for a reset. */
    public static final Duration MAXIMUM_RETRY_DELAY = Duration.ofMinutes(5);

    private final Duration retryAfter;

    public GithubRateLimitException(String message, HttpStatusCode statusCode,
                                    Duration retryAfter) {
        super(message, statusCode);
        this.retryAfter = clamp(retryAfter);
    }

    /**
     * The duration to wait before retrying, clamped between
     * {@link #MINIMUM_RETRY_DELAY} and {@link #MAXIMUM_RETRY_DELAY}.
     */
    public Duration getRetryAfter() {
        return retryAfter;
    }

    /**
     * Factory for 429 Too Many Requests using the {@code Retry-After} header.
     *
     * @param retryAfterSeconds value of the {@code Retry-After} header (seconds)
     */
    public static GithubRateLimitException fromRetryAfterHeader(long retryAfterSeconds) {
        return new GithubRateLimitException(
                "GitHub rate limit exceeded (429). Retry after %d seconds."
                        .formatted(retryAfterSeconds),
                HttpStatus.TOO_MANY_REQUESTS,
                Duration.ofSeconds(retryAfterSeconds)
        );
    }

    /**
     * Factory for 403 Forbidden with depleted rate-limit quota, using the
     * {@code X-RateLimit-Reset} Unix timestamp.
     *
     * @param resetEpochSeconds Unix timestamp at which the rate-limit window resets
     * @param nowEpochSeconds   current time as a Unix timestamp (for testability)
     */
    public static GithubRateLimitException fromRateLimitReset(
            long resetEpochSeconds, long nowEpochSeconds) {
        long secondsUntilReset = Math.max(0, resetEpochSeconds - nowEpochSeconds);
        return new GithubRateLimitException(
                "GitHub rate limit exhausted (403). Reset in %d seconds."
                        .formatted(secondsUntilReset),
                HttpStatus.FORBIDDEN,
                Duration.ofSeconds(secondsUntilReset)
        );
    }

    /** Clamps the duration between the defined minimum and maximum values. */
    private static Duration clamp(Duration d) {
        if (d == null || d.compareTo(MINIMUM_RETRY_DELAY) < 0) return MINIMUM_RETRY_DELAY;
        if (d.compareTo(MAXIMUM_RETRY_DELAY) > 0) return MAXIMUM_RETRY_DELAY;
        return d;
    }
}
