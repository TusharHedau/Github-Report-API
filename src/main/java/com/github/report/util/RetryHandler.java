package com.github.report.util;

import com.github.report.config.GithubProperties;
import com.github.report.exception.GithubNotFoundException;
import com.github.report.exception.GithubRateLimitException;
import com.github.report.exception.GithubUnauthorizedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import reactor.util.retry.Retry.RetrySignal;

import java.time.Duration;

/**
 * Builds a {@link Retry} specification for GitHub API calls that handles:
 *
 * <ol>
 *   <li><b>Rate-limit errors</b> ({@link GithubRateLimitException}) — waits the
 *       exact duration specified in the GitHub response headers before retrying.</li>
 *   <li><b>Transient errors</b> (network timeouts, 5xx) — retries with exponential
 *       backoff: {@code delay = min(initialBackoff × 2^attempt, maxBackoff)}.</li>
 *   <li><b>Non-retryable errors</b> (401, 404) — propagated immediately without
 *       any retry so the caller gets an instant, actionable error.</li>
 * </ol>
 *
 * <h2>Why {@code Retry.from()} over {@code Retry.backoff()}?</h2>
 * <p>{@code Retry.backoff()} provides built-in exponential backoff but offers no
 * hook to inspect the exception type and choose a <em>different</em> delay for
 * rate-limit errors. {@code Retry.from()} accepts a {@code Function<Flux<RetrySignal>, Publisher<?>>}
 * giving full control over when to retry and with what delay — exactly what we need.
 *
 * <h2>Thread safety</h2>
 * <p>The returned {@link Retry} spec is stateless and immutable — safe to share
 * across all WebClient call sites.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetryHandler {

    private final GithubProperties githubProperties;

    /**
     * Builds and returns the GitHub-aware {@link Retry} specification.
     *
     * <p>Attach to a reactive pipeline with:
     * <pre>{@code
     *   githubWebClient.get().uri(...).retrieve()
     *       .bodyToMono(...)
     *       .retryWhen(retryHandler.buildRetrySpec());
     * }</pre>
     *
     * @return a configured {@link Retry} spec
     */
    public Retry buildRetrySpec() {
        GithubProperties.Retry config = githubProperties.retry();

        return Retry.from(companion -> companion
                /*
                 * concatMap processes one retry signal at a time in order.
                 * Each emission from `companion` represents one failed attempt.
                 * We return a publisher that either:
                 *   - emits a value (after a delay) → triggers the next retry
                 *   - errors → propagates the terminal error upstream
                 */
                .concatMap(signal -> decideRetry(signal, config))
        );
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Core retry decision logic
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Decides whether to retry and how long to wait based on the failure type.
     *
     * @param signal the {@link RetrySignal} from the reactive retry companion
     * @param config the retry configuration from {@link GithubProperties}
     * @return a {@link Mono} that either delays (triggering retry) or errors (aborting)
     */
    private Mono<Long> decideRetry(RetrySignal signal,
                                   GithubProperties.Retry config) {
        Throwable failure = signal.failure();
        long attempt      = signal.totalRetries(); // 0-based: 0 = first retry

        // ── Non-retryable: surface immediately ────────────────────────────────
        if (isNonRetryable(failure)) {
            log.debug("Non-retryable error ({}): propagating immediately.",
                    failure.getClass().getSimpleName());
            return Mono.error(failure);
        }

        // ── Attempts exhausted ────────────────────────────────────────────────
        if (attempt >= config.maxAttempts()) {
            log.error("GitHub API retry attempts exhausted after {} tries. Last error: {}",
                    config.maxAttempts(), failure.getMessage());
            return Mono.error(failure);
        }

        // ── Rate-limit: use the header-derived delay ──────────────────────────
        if (failure instanceof GithubRateLimitException rateLimitEx) {
            Duration delay = rateLimitEx.getRetryAfter();
            log.warn("Rate limited by GitHub. Waiting {}s before retry {}/{}. Reason: {}",
                    delay.getSeconds(), attempt + 1, config.maxAttempts(),
                    failure.getMessage());
            return Mono.delay(delay).map(v -> attempt);
        }

        // ── Transient error: exponential backoff ──────────────────────────────
        Duration backoff = calculateExponentialBackoff(attempt, config);
        log.warn("Transient GitHub API error (attempt {}/{}). Retrying in {}ms. Cause: {}",
                attempt + 1, config.maxAttempts(),
                backoff.toMillis(), failure.getMessage());
        return Mono.delay(backoff).map(v -> attempt);
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} for errors that should never be retried.
     *
     * <ul>
     *   <li>401 — bad token: retrying will always fail</li>
     *   <li>404 — not found: the resource won't appear after a retry</li>
     * </ul>
     */
    private boolean isNonRetryable(Throwable failure) {
        return failure instanceof GithubUnauthorizedException
                || failure instanceof GithubNotFoundException;
    }

    /**
     * Calculates the exponential backoff delay for a given attempt number.
     *
     * <p>Formula: {@code delay = initialBackoff × 2^attempt}, capped at {@code maxBackoff}.
     *
     * <p>Examples with default config (initial=1000ms, max=30000ms):
     * <ul>
     *   <li>Attempt 0: 1000 × 2^0 = 1000 ms</li>
     *   <li>Attempt 1: 1000 × 2^1 = 2000 ms</li>
     *   <li>Attempt 2: 1000 × 2^2 = 4000 ms</li>
     *   <li>Attempt 5: 1000 × 2^5 = 32000 ms → capped at 30000 ms</li>
     * </ul>
     *
     * @param attempt 0-based retry attempt index
     * @param config  retry configuration properties
     * @return clamped backoff duration
     */
    private Duration calculateExponentialBackoff(long attempt,
                                                 GithubProperties.Retry config) {
        long delayMs = config.initialBackoffMs() * (1L << Math.min(attempt, 30));
        long cappedMs = Math.min(delayMs, config.maxBackoffMs());
        return Duration.ofMillis(cappedMs);
    }
}
