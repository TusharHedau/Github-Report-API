package com.github.report.model;

import java.util.List;

/**
 * Pairs a single {@link GithubRepo} with the complete list of its
 * {@link GithubCollaborator}s after all pagination pages have been consumed.
 *
 * <p>This is an intermediate domain object used inside the reactive pipeline:
 * <pre>
 *   fetchAllRepositories()          → Flux&lt;GithubRepo&gt;
 *     .flatMap(fetchCollaborators)  → Flux&lt;RepoCollaborators&gt;
 *       .collectList()              → Mono&lt;List&lt;RepoCollaborators&gt;&gt;
 *         .map(aggregate)          → Mono&lt;ReportResponse&gt;
 * </pre>
 *
 * <p>It is never serialised to JSON; it exists only between the client layer
 * and the aggregation logic in {@code ReportService}.
 *
 * @param repo          the repository metadata
 * @param collaborators all collaborators with access to this repository
 */
public record RepoCollaborators(
        GithubRepo repo,
        List<GithubCollaborator> collaborators
) {

    /**
     * Convenience accessor for the repository's short name.
     * Avoids calling {@code repoCollaborators.repo().name()} everywhere.
     *
     * @return the repository name (e.g. {@code "spring-framework"})
     */
    public String repoName() {
        return repo.name();
    }

    /**
     * Convenience accessor for the owner login.
     *
     * @return the owner's GitHub login (e.g. {@code "spring-projects"})
     */
    public String ownerLogin() {
        return repo.owner().login();
    }
}
