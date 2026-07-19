package com.github.report.service;

import com.github.report.config.CacheConfig;
import com.github.report.dto.OrganizationReport;
import com.github.report.dto.UserReport;
import com.github.report.model.GithubCollaborator;
import com.github.report.model.RepoCollaborators;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Service responsible for aggregating repository and collaborator details
 * into a structured, user-centric access report.
 *
 * <h2>Aggregation Logic</h2>
 * <p>While the GitHub API returns data centered around repositories (i.e. Repository -> Collaborators),
 * our functional requirements require the report to be centered around users (i.e. User -> Repositories).
 *
 * <p>This service subscribes to the reactive stream of {@link RepoCollaborators} from {@link GithubService},
 * collects all entries, and aggregates them in-memory:
 * <pre>
 *   List&lt;RepoCollaborators&gt;
 *     └─ stream()
 *          └─ For each repo, iterate over collaborators
 *               └─ Group in a Map&lt;String, List&lt;String&gt;&gt; (username -> list of repository names)
 * </pre>
 *
 * <h2>Sorting & Determinism</h2>
 * <ul>
 *   <li>Collaborators are sorted alphabetically by their username.</li>
 *   <li>The list of repositories for each collaborator is sorted alphabetically.</li>
 * </ul>
 * This guarantees a deterministic response payload, making caching, diffing, and testing predictable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final GithubService githubService;

    /**
     * Generates a repository access report for a given organization.
     *
     * @param org the name of the GitHub organization (e.g. {@code "spring-projects"})
     * @return a {@link Mono} emitting the completed {@link OrganizationReport}
     */
    @Cacheable(value = CacheConfig.CACHE_ORG_REPORTS, key = "#org")
    public Mono<OrganizationReport> generateReport(String org) {
        log.info("ReportService: starting report generation for organization '{}' (Cache miss)", org);

        return githubService.fetchReposWithCollaborators(org)
                .collectList()
                .map(repoCollabsList -> aggregate(org, repoCollabsList))
                .doOnSuccess(report -> log.info("ReportService: completed report for '{}' with {} users.",
                        org, report.users().size()));
    }

    /**
     * Performs the in-memory pivot from Repository -> Users to User -> Repositories.
     *
     * @param org        the organization name
     * @param repoCollabs the flat list of repositories and their collaborators
     * @return the aggregated {@link OrganizationReport}
     */
    private OrganizationReport aggregate(String org, List<RepoCollaborators> repoCollabs) {
        // Map from collaborator login username to the list of repository names they have access to
        Map<String, List<String>> userToReposMap = new HashMap<>();

        for (RepoCollaborators rc : repoCollabs) {
            String repoName = rc.repoName();
            for (GithubCollaborator collaborator : rc.collaborators()) {
                String username = collaborator.login();
                
                // Add this repository name to the collaborator's list of accessible repositories
                userToReposMap
                        .computeIfAbsent(username, k -> new ArrayList<>())
                        .add(repoName);
            }
        }

        // Convert the map to a sorted list of UserReport DTOs
        List<UserReport> userReports = userToReposMap.entrySet().stream()
                .map(entry -> {
                    String username = entry.getKey();
                    List<String> repos = entry.getValue();
                    // Sort the repositories alphabetically
                    Collections.sort(repos);
                    return new UserReport(username, repos);
                })
                // Sort users alphabetically by username
                .sorted((u1, u2) -> u1.username().compareToIgnoreCase(u2.username()))
                .toList();

        return new OrganizationReport(org, userReports);
    }
}
