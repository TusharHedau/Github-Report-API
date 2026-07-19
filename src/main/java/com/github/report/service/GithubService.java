package com.github.report.service;

import com.github.report.client.GithubClient;
import com.github.report.config.GithubProperties;
import com.github.report.model.GithubRepo;
import com.github.report.model.RepoCollaborators;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Orchestrates GitHub API calls to build the raw data needed for report generation.
 *
 * <h2>Phase 4 — Parallel collaborator fetching</h2>
 *
 * <p>The core challenge is fetching collaborators for 100+ repositories efficiently.
 * A naive sequential approach would issue one HTTP request after another, taking
 * O(n × page_latency) time. Instead we use {@link Flux#flatMap} with a bounded
 * concurrency level so m
 * ultiple repos are processed simultaneously:
 *
 * <pre>
 *   Flux&lt;GithubRepo&gt;            (all repos, one at a time from the stream)
 *     └─ flatMap(concurrency=10) ─ subscribe to up to 10 inner Monos at once
 *          └─ fetchCollaborators → collectList → RepoCollaborators
 *   = Flux&lt;RepoCollaborators&gt;   (unordered; arrives as each inner Mono completes)
 * </pre>
 *
 * <h2>Why flatMap and not concatMap / merge?</h2>
 * <ul>
 *   <li>{@code concatMap} — sequential, same as a for-loop. Correct but slow.</li>
 *   <li>{@code flatMap} — concurrent with a bounded inner subscription count.
 *       This is what we want: parallel without unbounded thread consumption.</li>
 *   <li>Plain {@code merge} — would require materialising all repos first and
 *       then merging, which is more verbose and less memory-efficient.</li>
 * </ul>
 *
 * <h2>Why bounded concurrency?</h2>
 * <p>GitHub enforces a rate limit of 5,000 authenticated requests per hour.
 * Unbounded concurrency (e.g. flatMap with no limit) would fire all 100+
 * collaborator requests at once, potentially burning the entire rate budget
 * in seconds. A concurrency of 10 gives a 10× speedup over sequential while
 * spreading request load over time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubService {

    private final GithubClient githubClient;
    private final GithubProperties githubProperties;

    // ═════════════════════════════════════════════════════════════════════════════
    // Public API
    // ═════════════════════════════════════════════════════════════════════════════

    /**
     * Fetches all repositories for the given organisation as a reactive stream.
     *
     * @param org the GitHub organisation slug
     * @return {@link Flux} of {@link GithubRepo}; empty if the org has no repos
     */
    public Flux<GithubRepo> getRepositories(String org) {
        log.info("Fetching repositories for org '{}'", org);
        return githubClient.fetchAllRepositories(org);
    }

    /**
     * Fetches all repositories <em>and</em> their collaborators for the given
     * organisation, using parallel HTTP calls bounded by the configured concurrency.
     *
     * <p><b>Processing flow:</b>
     * <ol>
     *   <li>Stream all repos from {@link GithubClient#fetchAllRepositories}.</li>
     *   <li>For each repo, subscribe to a collaborator-fetch pipeline using
     *       {@code Flux.flatMap} with {@code concurrency} simultaneous subscriptions.</li>
     *   <li>Each inner pipeline: fetch all collaborator pages → collect to {@code List}
     *       → wrap in {@link RepoCollaborators}.</li>
     *   <li>Errors on a single repo are logged and swallowed ({@code onErrorResume})
     *       so one inaccessible repo does not abort the entire report.</li>
     * </ol>
     *
     * @param org the GitHub organisation slug
     * @return {@link Flux} of {@link RepoCollaborators} — one entry per repo,
     *         in the order they complete (not necessarily insertion order)
     */
    public Flux<RepoCollaborators> fetchReposWithCollaborators(String org) {
        int concurrency = githubProperties.concurrency();
        log.info("Fetching repos + collaborators for org '{}' (concurrency={})",
                org, concurrency);

        return githubClient.fetchAllRepositories(org)
                /*
                 * flatMap with the concurrency parameter:
                 * ─ At most `concurrency` repos have their collaborator HTTP calls
                 *   in-flight at any given moment.
                 * ─ As each inner Mono<RepoCollaborators> completes, flatMap
                 *   automatically starts the next pending repo.
                 * ─ Results arrive out-of-order (whoever finishes first), which is
                 *   fine because the aggregation step (Phase 6) groups by user anyway.
                 */
                .flatMap(repo -> fetchCollaboratorsForRepo(repo)
                                .onErrorResume(ex -> handleCollaboratorError(repo, ex)),
                        concurrency);
    }

    /**
     * Collects all collaborators for a single repository into a {@link RepoCollaborators}.
     *
     * <p>{@code collectList()} subscribes to the paginated {@link Flux} and buffers
     * every emitted item into an in-memory list. The list is bounded by the number
     * of collaborators on a single repo — typically &lt;1,000, well within heap limits.
     *
     * @param repo the repository to fetch collaborators for
     * @return a {@link Mono} emitting exactly one {@link RepoCollaborators}
     */
    private Mono<RepoCollaborators> fetchCollaboratorsForRepo(GithubRepo repo) {
        String owner = repo.owner().login();
        String repoName = repo.name();

        return githubClient.fetchAllCollaborators(owner, repoName)
                .collectList()
                .map(collaborators -> {
                    log.debug("Repo '{}/{}' → {} collaborator(s)",
                            owner, repoName, collaborators.size());
                    return new RepoCollaborators(repo, collaborators);
                });
    }

    /**
     * Fallback handler for errors while fetching collaborators for a single repo.
     *
     * <p>Common causes:
     * <ul>
     *   <li>HTTP 403 — the PAT lacks {@code repo} scope for private repos.</li>
     *   <li>HTTP 404 — repo was deleted between the repo-list and collaborator calls.</li>
     *   <li>Timeout — transient network blip on a specific endpoint.</li>
     * </ul>
     *
     * <p>The strategy here is to log the error and return an empty
     * {@link RepoCollaborators} (zero collaborators) so the repo still appears
     * in the report, but without collaborator data. This is preferable to aborting
     * the entire report for one inaccessible repo.
     *
     * @param repo the repo that failed
     * @param ex   the error that occurred
     * @return a {@link Mono} emitting an empty {@link RepoCollaborators}
     */
    private Mono<RepoCollaborators> handleCollaboratorError(GithubRepo repo, Throwable ex) {
        log.warn("Failed to fetch collaborators for repo '{}' — skipping. Reason: {}",
                repo.name(), ex.getMessage());
        log.error("Repository: {} | Error: ", repo.name(), ex);
        return Mono.just(new RepoCollaborators(repo, List.of()));
    }

}
