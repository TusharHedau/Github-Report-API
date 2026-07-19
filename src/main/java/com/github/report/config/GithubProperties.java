package com.github.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed configuration properties bound from the {@code github.*} namespace
 * in {@code application.yml}.
 *
 * <p>Using a dedicated {@code @ConfigurationProperties} record (Java 21 feature) rather
 * than scattered {@code @Value} annotations keeps all GitHub-related settings in one
 * place and makes them easy to validate and mock in tests.
 *
 * <p>Example YAML mapping:
 * <pre>
 * github:
 *   base-url: https://api.github.com
 *   token: ${GITHUB_TOKEN}
 *   api-version: 2022-11-28
 *   page-size: 100
 *   retry:
 *     max-attempts: 3
 *     initial-backoff-ms: 1000
 *     max-backoff-ms: 30000
 *   timeout:
 *     connect-seconds: 10
 *     read-seconds: 30
 * </pre>
 *
 * @param baseUrl      Base URL of the GitHub REST API (overrideable for GHE / tests)
 * @param token        Personal Access Token read from the {@code GITHUB_TOKEN} env var
 * @param apiVersion   GitHub API version header value
 * @param pageSize     Items per page for paginated list calls (max 100 per GitHub docs)
 * @param concurrency  Max number of repositories whose collaborators are fetched in
 *                     parallel via {@code Flux.flatMap}. Tune to stay within GitHub
 *                     rate limits (default: 10).
 * @param retry        Retry / exponential back-off settings
 * @param timeout      Connect and read timeout settings
 */
@ConfigurationProperties(prefix = "github")
public record GithubProperties(
        String baseUrl,
        String token,
        String apiVersion,
        int pageSize,
        int concurrency,
        Retry retry,
        Timeout timeout
) {

    /**
     * Retry / back-off configuration.
     *
     * @param maxAttempts       Total attempts before propagating the error
     * @param initialBackoffMs  First retry delay in milliseconds
     * @param maxBackoffMs      Maximum back-off ceiling in milliseconds
     */
    public record Retry(
            int maxAttempts,
            long initialBackoffMs,
            long maxBackoffMs
    ) {}

    /**
     * HTTP timeout configuration.
     *
     * @param connectSeconds  TCP connection timeout in seconds
     * @param readSeconds     Response read timeout in seconds
     */
    public record Timeout(
            int connectSeconds,
            int readSeconds
    ) {}
}
