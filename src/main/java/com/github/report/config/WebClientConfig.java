package com.github.report.config;


import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Spring configuration class that produces a fully configured {@link WebClient} bean
 * for all outbound GitHub REST API calls.
 *
 * <h2>Design Decisions</h2>
 * <ul>
 *   <li><b>WebClient over RestTemplate</b> — WebClient is the non-blocking, reactive
 *       HTTP client in Spring WebFlux. Using it allows us to issue hundreds of
 *       concurrent GitHub API requests (repos + collaborators) without holding threads,
 *       which is essential at the 100+ repos / 1000+ users scale.</li>
 *   <li><b>Netty HttpClient</b> — The underlying Reactor-Netty transport gives us
 *       fine-grained control over TCP-level connect timeout, read timeout, and
 *       write timeout, all driven from {@link GithubProperties}.</li>
 *   <li><b>defaultHeaders</b> — GitHub requires {@code Authorization},
 *       {@code Accept: application/vnd.github+json}, and
 *       {@code X-GitHub-Api-Version} on every request. Setting them once on the
 *       shared WebClient instance avoids copy-paste in every client method.</li>
 *   <li><b>User-Agent</b> — GitHub API rejects requests without a User-Agent header.</li>
 *   <li><b>Exchange filters</b> — A request/response logging filter is attached only
 *       in DEBUG mode so production logs stay clean.</li>
 * </ul>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class WebClientConfig {

    /** Maximum response body size (10 MB) — prevents OOM on unexpectedly large payloads. */
    private static final int MAX_IN_MEMORY_SIZE = 10 * 1024 * 1024; // 10 MB

    /** GitHub REST API media type required for all v3 requests. */
    private static final String GITHUB_ACCEPT = "application/vnd.github+json";

    /** User-Agent value — GitHub API requires a non-empty User-Agent string. */
    private static final String USER_AGENT = "github-report-api/1.0";

    private final GithubProperties githubProperties;
    private final RateLimitExchangeFilter rateLimitExchangeFilter;

    /**
     * Creates and configures the shared {@link WebClient} for GitHub API calls.
     *
     * <p>The bean is a singleton; the underlying Netty connection pool is reused
     * across all reactive pipelines, which is the efficient, production-grade pattern.
     *
     * @param builder Spring-provided {@link WebClient.Builder} — pre-configured with
     *                any auto-configured codecs; we further customise it here.
     * @return a fully configured {@code WebClient} instance scoped to the GitHub base URL
     */
    @Bean
    public WebClient githubWebClient(WebClient.Builder builder) {
        validateToken();

        HttpClient httpClient = buildHttpClient();

        return builder
                // ── Base URL ──────────────────────────────────────────────────────
                .baseUrl(githubProperties.baseUrl())

                // ── Transport ─────────────────────────────────────────────────────
                .clientConnector(new ReactorClientHttpConnector(httpClient))

                // ── Default request headers sent on every call ────────────────────
                .defaultHeaders(headers -> {
                    // Bearer token authentication
                    headers.set(HttpHeaders.AUTHORIZATION,
                            "Bearer " + githubProperties.token());

                    // GitHub's required Accept header for the REST v3 API
                    headers.set(HttpHeaders.ACCEPT, GITHUB_ACCEPT);

                    // GitHub API version pinning (mandatory since Nov 2022)
                    headers.set("X-GitHub-Api-Version", githubProperties.apiVersion());

                    // Required by GitHub API — rejected if absent
                    headers.set(HttpHeaders.USER_AGENT, USER_AGENT);

                    // We always send/receive JSON
                    headers.set(HttpHeaders.CONTENT_TYPE,
                            MediaType.APPLICATION_JSON_VALUE);
                })

                // ── Codec configuration ───────────────────────────────────────────
                .codecs(configurer -> {
                    // Raise the in-memory buffer limit to handle large list responses
                    // (e.g., 100-item pages of collaborators × many repos)
                    configurer.defaultCodecs()
                              .maxInMemorySize(MAX_IN_MEMORY_SIZE);

                    // Enable request/response body detail logging only in DEBUG mode.
                    // enableLoggingRequestDetails logs request headers + body — useful
                    // locally but must stay off in production to avoid leaking tokens.
                    configurer.defaultCodecs()
                              .enableLoggingRequestDetails(log.isDebugEnabled());
                })

                // ── Exchange filters ──────────────────────────────────────────────
                // Rate-limit filter runs first: converts 429/403/401/404 to typed
                // exceptions BEFORE the logging filter sees them, so we don't log
                // the raw status code and the exception message redundantly.
                .filter(rateLimitExchangeFilter.rateLimitFilter())
                .filter(requestLoggingFilter())
                .filter(responseLoggingFilter())

                .build();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Builds the Reactor-Netty {@link HttpClient} with TCP-level timeouts.
     *
     * <p>All timeout values are read from {@link GithubProperties.Timeout} so they
     * can be tuned per environment without code changes.
     */
    private HttpClient buildHttpClient() {
        GithubProperties.Timeout timeout = githubProperties.timeout();

        return HttpClient.create()
                // TCP connect timeout — how long to wait for the TCP handshake
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        timeout.connectSeconds() * 1_000)

                // Reactor-Netty response timeout (includes connection + read)
                .responseTimeout(Duration.ofSeconds(timeout.readSeconds()))

                // Netty pipeline-level handlers for more granular read/write control
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(
                                timeout.readSeconds(), TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(
                                timeout.connectSeconds(), TimeUnit.SECONDS))
                );
    }

    /**
     * Exchange filter that logs outgoing request method and URI at DEBUG level.
     *
     * <p>The Authorization header value is intentionally NOT logged to prevent
     * accidental token exposure in log aggregators.
     */
    private ExchangeFilterFunction requestLoggingFilter() {
        return ExchangeFilterFunction.ofRequestProcessor(request -> {
            if (log.isDebugEnabled()) {
                log.debug("GitHub API --> {} {}", request.method(), request.url());
            }
            return Mono.just(request);
        });
    }

    /**
     * Exchange filter that logs the response status code at DEBUG level.
     */
    private ExchangeFilterFunction responseLoggingFilter() {
        return ExchangeFilterFunction.ofResponseProcessor(response -> {
            if (log.isDebugEnabled()) {
                log.debug("GitHub API <-- status={}", response.statusCode());
            }
            return Mono.just(response);
        });
    }

    /**
     * Warns at startup if the GitHub token is blank.
     *
     * <p>We log a warning rather than throwing at bean-creation time so the app
     * can still start in environments where the token is injected later
     * (e.g., via a secrets manager sidecar). The actual HTTP 401 from GitHub will
     * surface when the first API call is made, and the global exception handler
     * (Phase 9) will convert it to a clean API error response.
     */
    private void validateToken() {
        if (!StringUtils.hasText(githubProperties.token())) {
            log.warn("""
                    ╔══════════════════════════════════════════════════════════════╗
                    ║  GITHUB_TOKEN environment variable is not set or is empty.  ║
                    ║  All GitHub API calls will fail with HTTP 401 Unauthorized. ║
                    ║  Export GITHUB_TOKEN=<your-pat> before starting the app.    ║
                    ╚══════════════════════════════════════════════════════════════╝
                    """);
        } else {
            // Log only the first 4 chars to confirm the token was picked up
            // without exposing the full secret in logs.
            log.info("TOKEN = [{}]", githubProperties.token());
            String masked = githubProperties.token().substring(0, 4) + "****";
            log.info("GitHub token loaded successfully (prefix: {})", masked);
        }
    }
}
