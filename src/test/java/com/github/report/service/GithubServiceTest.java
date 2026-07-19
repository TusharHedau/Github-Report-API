package com.github.report.service;

import com.github.report.client.GithubClient;
import com.github.report.config.GithubProperties;
import com.github.report.model.GithubCollaborator;
import com.github.report.model.GithubRepo;
import com.github.report.model.RepoCollaborators;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GithubService} — parallel collaborator fetching.
 *
 * <p>Uses Mockito to stub {@link GithubClient} so tests run without a network
 * or Spring context. {@link StepVerifier} from {@code reactor-test} asserts
 * the reactive stream behaviour.
 */
@DisplayName("GithubService")
@ExtendWith(MockitoExtension.class)
class GithubServiceTest {

    @Mock
    private GithubClient githubClient;

    private GithubService githubService;

    // ── Test fixtures ────────────────────────────────────────────────────────────

    private static final GithubRepo.Owner OWNER =
            new GithubRepo.Owner("acme-org");

    private static final GithubRepo REPO_A =
            GithubRepo.builder()
                    .id(1L).name("repo-a").fullName("acme-org/repo-a")
                    .owner(OWNER).isPrivate(false).fork(false).build();

    private static final GithubRepo REPO_B =
            GithubRepo.builder()
                    .id(2L).name("repo-b").fullName("acme-org/repo-b")
                    .owner(OWNER).isPrivate(false).fork(false).build();

    private static final GithubCollaborator ALICE =
            GithubCollaborator.builder()
                    .id(10L).login("alice").type("User").siteAdmin(false).build();

    private static final GithubCollaborator BOB =
            GithubCollaborator.builder()
                    .id(20L).login("bob").type("User").siteAdmin(false).build();

    @BeforeEach
    void setUp() {
        // Build a GithubProperties with concurrency=2 (tests run serially anyway
        // but the property must be present for constructor binding to work)
        GithubProperties props = new GithubProperties(
                "https://api.github.com",   // baseUrl
                "test-token",               // token
                "2022-11-28",               // apiVersion
                100,                        // pageSize
                2,                          // concurrency
                new GithubProperties.Retry(3, 1000L, 30000L),
                new GithubProperties.Timeout(10, 30)
        );
        githubService = new GithubService(githubClient, props);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // getRepositories
    // ═════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("getRepositories delegates to GithubClient and returns the Flux")
    void getRepositories_delegatesToClient() {
        when(githubClient.fetchAllRepositories("acme-org"))
                .thenReturn(Flux.just(REPO_A, REPO_B));

        StepVerifier.create(githubService.getRepositories("acme-org"))
                .expectNext(REPO_A)
                .expectNext(REPO_B)
                .verifyComplete();

        verify(githubClient, times(1)).fetchAllRepositories("acme-org");
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // fetchReposWithCollaborators — happy path
    // ═════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fetchReposWithCollaborators pairs each repo with its collaborators")
    void fetchReposWithCollaborators_happyPath_returnsRepoCollaborators() {
        when(githubClient.fetchAllRepositories("acme-org"))
                .thenReturn(Flux.just(REPO_A, REPO_B));
        when(githubClient.fetchAllCollaborators("acme-org", "repo-a"))
                .thenReturn(Flux.just(ALICE, BOB));
        when(githubClient.fetchAllCollaborators("acme-org", "repo-b"))
                .thenReturn(Flux.just(BOB));

        List<RepoCollaborators> results = githubService
                .fetchReposWithCollaborators("acme-org")
                .collectList()
                .block();

        assertThat(results).isNotNull().hasSize(2);

        RepoCollaborators repoA = results.stream()
                .filter(rc -> rc.repoName().equals("repo-a"))
                .findFirst().orElseThrow();
        assertThat(repoA.collaborators()).containsExactlyInAnyOrder(ALICE, BOB);

        RepoCollaborators repoB = results.stream()
                .filter(rc -> rc.repoName().equals("repo-b"))
                .findFirst().orElseThrow();
        assertThat(repoB.collaborators()).containsExactly(BOB);
    }

    // ═════════════════════════════════════════════════════════════════════════════
    // fetchReposWithCollaborators — error resilience
    // ═════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("fetchReposWithCollaborators continues when one repo's collaborator call fails")
    void fetchReposWithCollaborators_singleRepoFails_otherReposStillReturned() {
        when(githubClient.fetchAllRepositories("acme-org"))
                .thenReturn(Flux.just(REPO_A, REPO_B));

        // repo-a: collaborator fetch throws a runtime exception (e.g. 403 Forbidden)
        when(githubClient.fetchAllCollaborators("acme-org", "repo-a"))
                .thenReturn(Flux.error(new RuntimeException("403 Forbidden")));

        // repo-b: succeeds normally
        when(githubClient.fetchAllCollaborators("acme-org", "repo-b"))
                .thenReturn(Flux.just(BOB));

        List<RepoCollaborators> results = githubService
                .fetchReposWithCollaborators("acme-org")
                .collectList()
                .block();

        assertThat(results).isNotNull().hasSize(2);

        // repo-a should be present but with an empty collaborator list
        RepoCollaborators repoA = results.stream()
                .filter(rc -> rc.repoName().equals("repo-a"))
                .findFirst().orElseThrow();
        assertThat(repoA.collaborators()).isEmpty();

        // repo-b should be unaffected
        RepoCollaborators repoB = results.stream()
                .filter(rc -> rc.repoName().equals("repo-b"))
                .findFirst().orElseThrow();
        assertThat(repoB.collaborators()).containsExactly(BOB);
    }

    @Test
    @DisplayName("fetchReposWithCollaborators with no repos returns empty Flux")
    void fetchReposWithCollaborators_noRepos_returnsEmpty() {
        when(githubClient.fetchAllRepositories("empty-org"))
                .thenReturn(Flux.empty());

        StepVerifier.create(githubService.fetchReposWithCollaborators("empty-org"))
                .verifyComplete();

        // Collaborator endpoint must never be called for an org with zero repos
        verify(githubClient, never()).fetchAllCollaborators(anyString(), anyString());
    }

    @Test
    @DisplayName("fetchReposWithCollaborators with repo having zero collaborators returns empty list")
    void fetchReposWithCollaborators_repoHasNoCollaborators_returnsEmptyList() {
        when(githubClient.fetchAllRepositories("acme-org"))
                .thenReturn(Flux.just(REPO_A));
        when(githubClient.fetchAllCollaborators("acme-org", "repo-a"))
                .thenReturn(Flux.empty());

        List<RepoCollaborators> results = githubService
                .fetchReposWithCollaborators("acme-org")
                .collectList()
                .block();

        assertThat(results).hasSize(1);
        assertThat(results.get(0).collaborators()).isEmpty();
    }
}
