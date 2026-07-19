package com.github.report.client;

import com.github.report.config.GithubProperties;
import com.github.report.model.GithubCollaborator;
import com.github.report.model.GithubRepo;
import com.github.report.util.LinkHeaderParser;
import com.github.report.util.RetryHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/**
 * Low-level HTTP client responsible for all communication with the GitHub REST API.
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Fetch all repositories for a GitHub organisation with full pagination.</li>
 *   <li>Fetch all collaborators for a repository with full pagination.</li>
 *   <li>Parse {@code Link} headers to walk multiple pages transparently.</li>
 * </ul>
 *
 * <h2>Design decisions</h2>
 * <ul>
 *   <li><b>Returns {@link Flux}</b> for list endpoints — callers can subscribe
 *       reactively or collect to a {@code List} depending on the context.</li>
 *   <li><b>Pagination via recursion</b> — {@code fetchPage} calls itself for
 *       every {@code rel="next"} URL, producing a flat stream of items. This
 *       avoids blocking loops and composes naturally with the reactive pipeline.</li>
 *   <li><b>Absolute URL support</b> — GitHub's {@code Link} header contains the
 *       full absolute URL for the next page. We use {@code uri(URI::create)} to
 *       bypass the base-URL override and hit the exact URL provided.</li>
 *   <li><b>ParameterizedTypeReference</b> — required to deserialise
 *       {@code List<GithubRepo>} / {@code List<GithubCollaborator>} without
 *       type erasure issues.</li>
 * </ul>
 *
 * <p><b>Note on rate-limiting and retry:</b> These concerns are intentionally
 * absent here and will be added as a reactive operator decorator in Phase 5.
 * Keeping the client pure makes it easier to unit-test pagination logic
 * independently of retry logic.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GithubClient {

    // ── Type references ──────────────────────────────────────────────────────────
    // Declared as constants to avoid allocating a new ParameterizedTypeReference
    // on every method call (they are stateless and reusable).

    private static final ParameterizedTypeReference<List<GithubRepo>> REPO_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private static final ParameterizedTypeReference<List<GithubCollaborator>> COLLABORATOR_LIST_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient githubWebClient;
    private final GithubProperties githubProperties;
    private final LinkHeaderParser linkHeaderParser;
    private final RetryHandler retryHandler;

    // ═════════════════════════════════════════════════════════════════════════════
    // Public API
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Fetches <b>all</b> repositories for the given GitHub organisation, transparently
     * walking every pagination page via {@code Link: rel="next"} headers.
     *
     * <p>The first request hits {@code GET /orgs/{org}/repos?per_page={pageSize}&type=all}.
     * Each subsequent request uses the absolute next-page URL returned by GitHub.
     *
     * @param org the GitHub organisation slug (e.g. {@code "spring-projects"})
     * @return a {@link Flux} emitting one {@link GithubRepo} per repository,
     *         completing when all pages have been consumed
     */
    public Flux<GithubRepo> fetchAllRepositories(String org) {
        String firstPageUri = "/orgs/%s/repos?per_page=%d&type=all"
                .formatted(org, githubProperties.pageSize());

        log.info("Fetching repositories for org '{}' (pageSize={})", org,
                githubProperties.pageSize());

        return fetchRepoPage(firstPageUri, 1);
    }

    /**
     * Fetches <b>all</b> collaborators for a single repository, walking all pages.
     *
     * <p>Calls {@code GET /repos/{owner}/{repo}/collaborators?per_page={pageSize}&affiliation=all}.
     * The {@code affiliation=all} parameter is important: without it, GitHub only
     * returns <em>direct</em> collaborators and excludes team members.
     *
     * @param owner the repository owner / org slug
     * @param repo  the repository name
     * @return a {@link Flux} emitting one {@link GithubCollaborator} per collaborator,
     *         completing when all pages have been consumed
     */
    public Flux<GithubCollaborator> fetchAllCollaborators(String owner, String repo) {
        String firstPageUri =
                "/repos/%s/%s/collaborators?per_page=%d&affiliation=all"
                        .formatted(owner, repo, githubProperties.pageSize());

        log.debug("Fetching collaborators for {}/{}", owner, repo);

        return fetchCollaboratorPage(firstPageUri, 1);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // Private pagination helpers
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Recursively fetches a single page of repositories and continues to the
     * next page if a {@code rel="next"} link is present in the response headers.
     *
     * <p><b>How pagination works:</b>
     * <ol>
     *   <li>Issue a {@code GET} to {@code pageUri} using the shared {@link WebClient}.</li>
     *   <li>Capture the full response (headers + body) via {@code toEntityList}.</li>
     *   <li>Emit all items from the current page as a {@link Flux}.</li>
     *   <li>If the {@code Link} header contains {@code rel="next"}, concatenate
     *       another recursive call for the next page.</li>
     *   <li>If there is no next link, the recursion terminates and the overall
     *       {@code Flux} completes.</li>
     * </ol>
     *
     * @param pageUri  the URI of the page to fetch (first call: relative; subsequent: absolute)
     * @param pageNum  current page number, used only for logging
     * @return a {@link Flux} of {@link GithubRepo} for this page and all subsequent pages
     */
    private Flux<GithubRepo> fetchRepoPage(String pageUri, int pageNum) {
        return githubWebClient.get()
                .uri(pageUri)
                .retrieve()
                /*
                 * toEntityList captures the ResponseEntity<List<T>>, giving access to
                 * both the body (list of repos) and the headers (Link header for pagination).
                 * This is preferable to bodyToFlux because the Link header is only available
                 * on the ResponseEntity, not the raw Flux<T>.
                 */
                .toEntityList(GithubRepo.class)
                // Retry is applied per-page so a transient failure on page N
                // retries page N only — not the whole repo fetch from page 1.
                .retryWhen(retryHandler.buildRetrySpec())
                .flatMapMany(response -> {
                    List<GithubRepo> repos = response.getBody();
                    if (repos == null || repos.isEmpty()) {
                        log.debug("Repo page {} returned empty — stopping pagination.", pageNum);
                        return Flux.empty();
                    }

                    log.debug("Repo page {}: received {} repositories.", pageNum, repos.size());

                    // Emit all repos from the current page
                    Flux<GithubRepo> currentPage = Flux.fromIterable(repos);

                    // Check for a next page and recursively concatenate it
                    Optional<String> nextUrl = linkHeaderParser.extractNextUrl(
                            response.getHeaders());

                    if (nextUrl.isPresent()) {
                        // Concatenation ensures ordering is preserved page by page
                        return Flux.concat(
                                currentPage,
                                fetchRepoPage(nextUrl.get(), pageNum + 1)
                        );
                    }

                    return currentPage;
                })
                .doOnComplete(() -> log.debug("Finished fetching all repo pages (last page: {}).",
                        pageNum));
    }

    /**
     * Recursively fetches a single page of collaborators and continues to the
     * next page if a {@code rel="next"} link is present.
     *
     * <p>Uses the same pagination pattern as {@link #fetchRepoPage} — see that
     * method's Javadoc for a detailed explanation of the approach.
     *
     * @param pageUri  the URI of the page to fetch
     * @param pageNum  current page number, used only for logging
     * @return a {@link Flux} of {@link GithubCollaborator} for this and all subsequent pages
     */
    private Flux<GithubCollaborator> fetchCollaboratorPage(String pageUri, int pageNum) {
        return githubWebClient.get()
                .uri(pageUri)
                .retrieve()
                .toEntityList(GithubCollaborator.class)
                // Same per-page retry strategy as fetchRepoPage
                .retryWhen(retryHandler.buildRetrySpec())
                .flatMapMany(response -> {
                    List<GithubCollaborator> collaborators = response.getBody();
                    if (collaborators == null || collaborators.isEmpty()) {
                        log.debug("Collaborator page {} returned empty — stopping pagination.",
                                pageNum);
                        return Flux.empty();
                    }

                    log.debug("Collaborator page {}: received {} collaborators.",
                            pageNum, collaborators.size());

                    Flux<GithubCollaborator> currentPage = Flux.fromIterable(collaborators);

                    Optional<String> nextUrl = linkHeaderParser.extractNextUrl(
                            response.getHeaders());

                    if (nextUrl.isPresent()) {
                        return Flux.concat(
                                currentPage,
                                fetchCollaboratorPage(nextUrl.get(), pageNum + 1)
                        );
                    }

                    return currentPage;
                });
    }

    /**
     * Health-check method: verifies connectivity to the GitHub API by calling
     * {@code GET /rate_limit}.
     *
     * <p>Used by the actuator health indicator (added in a later phase) and by
     * integration tests to confirm the WireMock stub is reachable.
     *
     * @return a {@link Mono} that emits the raw JSON string of the rate-limit
     *         response, or an error signal if GitHub is unreachable
     */
    public Mono<String> checkRateLimit() {
        return githubWebClient.get()
                .uri("/rate_limit")
                .retrieve()
                .bodyToMono(String.class);
    }
}
