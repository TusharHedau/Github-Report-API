package com.github.report.exception;

import org.springframework.http.HttpStatusCode;

/**
 * Base runtime exception for all GitHub API errors surfaced by this application.
 *
 * <p>Extending {@link RuntimeException} (unchecked) keeps the reactive pipeline
 * clean — callers are not forced to declare checked exceptions in lambda bodies.
 *
 * <p>Subclasses carry context specific to each error category:
 * <ul>
 *   <li>{@link GithubRateLimitException} — rate-limit (429 / 403 with X-RateLimit-Remaining=0)</li>
 *   <li>{@link GithubNotFoundException} — 404 organisation / repo not found</li>
 *   <li>{@link GithubUnauthorizedException} — 401 bad / missing token</li>
 * </ul>
 */
public class GithubApiException extends RuntimeException {

    private final HttpStatusCode statusCode;

    public GithubApiException(String message, HttpStatusCode statusCode) {
        super(message);
        this.statusCode = statusCode;
    }

    public GithubApiException(String message, HttpStatusCode statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public HttpStatusCode getStatusCode() {
        return statusCode;
    }
}
