package com.github.report.util;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Lightweight startup probe that verifies the GitHub PAT is valid by calling
 * the {@code GET /user} endpoint (requires no specific scope — any valid token works).
 *
 * <h2>Why a startup probe?</h2>
 * <ul>
 *   <li>Fails fast with a clear log message instead of a cryptic 401 on the first
 *       real report request.</li>
 *   <li>Runs <em>after</em> the application context is fully started
 *       ({@link ApplicationReadyEvent}), so it doesn't block boot or prevent the
 *       actuator /health endpoint from responding.</li>
 *   <li>Non-fatal — authentication failures are logged as WARN, not ERROR, so the
 *       app keeps running. This allows tokens to be rotated without a restart in
 *       environments where the secret is updated out-of-band.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubTokenValidator {

    private final WebClient githubWebClient;

    /**
     * Calls {@code GET /user} on GitHub once the application is ready.
     *
     * <p>The call is made reactively and subscribed with an error handler so the
     * subscriber chain never propagates an exception back to the event publisher.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void validateToken() {
        log.info("Validating GitHub token against GET /user ...");

        githubWebClient.get()
                .uri("/user")
                .retrieve()
                .bodyToMono(String.class)
                .subscribe(
                        response -> log.info("GitHub token validation successful — authenticated as a GitHub user."),
                        error -> {
                            if (error instanceof WebClientResponseException wcre) {
                                log.warn("GitHub token validation failed [HTTP {}]: {}. "
                                         + "Report requests will likely fail with 401.",
                                        wcre.getStatusCode(), wcre.getMessage());
                            } else {
                                log.warn("GitHub token validation failed (network error): {}. "
                                         + "Check GITHUB_TOKEN and network connectivity.",
                                        error.getMessage());
                            }
                        }
                );
    }
}
