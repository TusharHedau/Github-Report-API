package com.github.report.service;

import com.github.report.dto.OrganizationReport;
import com.github.report.dto.UserReport;
import com.github.report.model.GithubCollaborator;
import com.github.report.model.GithubRepo;
import com.github.report.model.RepoCollaborators;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReportService}.
 * Verifies that the Repository -> User pivot works correctly, handles multiple
 * users sharing repositories, and sorts both users and repositories alphabetically.
 */
@DisplayName("ReportService")
@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private GithubService githubService;

    @InjectMocks
    private ReportService reportService;

    private static final GithubRepo.Owner OWNER = new GithubRepo.Owner("acme-org");

    private static final GithubRepo REPO_1 = GithubRepo.builder().id(1L).name("beta-repo").owner(OWNER).build();
    private static final GithubRepo REPO_2 = GithubRepo.builder().id(2L).name("alpha-repo").owner(OWNER).build();

    private static final GithubCollaborator USER_BOB = GithubCollaborator.builder().id(10L).login("bob").build();
    private static final GithubCollaborator USER_ALICE = GithubCollaborator.builder().id(20L).login("alice").build();
    private static final GithubCollaborator USER_CHARLIE = GithubCollaborator.builder().id(30L).login("charlie").build();

    @Test
    @DisplayName("aggregates and pivots Repository-to-Users into User-to-Repositories with sorting")
    void generateReport_pivotsAndSortsCorrectly() {
        // repo-1 ("beta-repo") has bob and alice
        RepoCollaborators collabs1 = new RepoCollaborators(REPO_1, List.of(USER_BOB, USER_ALICE));
        // repo-2 ("alpha-repo") has charlie and alice
        RepoCollaborators collabs2 = new RepoCollaborators(REPO_2, List.of(USER_CHARLIE, USER_ALICE));

        when(githubService.fetchReposWithCollaborators("acme-org"))
                .thenReturn(Flux.just(collabs1, collabs2));

        StepVerifier.create(reportService.generateReport("acme-org"))
                .assertNext(report -> {
                    assertThat(report.organization()).isEqualTo("acme-org");
                    
                    List<UserReport> users = report.users();
                    // Alice, Bob, Charlie should be alphabetically sorted
                    assertThat(users).hasSize(3);
                    
                    // Alice: has access to both beta-repo and alpha-repo, sorted alphabetically to [alpha-repo, beta-repo]
                    assertThat(users.get(0).username()).isEqualTo("alice");
                    assertThat(users.get(0).repositories()).containsExactly("alpha-repo", "beta-repo");
                    
                    // Bob: has access only to beta-repo
                    assertThat(users.get(1).username()).isEqualTo("bob");
                    assertThat(users.get(1).repositories()).containsExactly("beta-repo");
                    
                    // Charlie: has access only to alpha-repo
                    assertThat(users.get(2).username()).isEqualTo("charlie");
                    assertThat(users.get(2).repositories()).containsExactly("alpha-repo");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("handles empty repository list or organization with no repos gracefully")
    void generateReport_emptyOrNoRepos_returnsEmptyReport() {
        when(githubService.fetchReposWithCollaborators("empty-org"))
                .thenReturn(Flux.empty());

        StepVerifier.create(reportService.generateReport("empty-org"))
                .assertNext(report -> {
                    assertThat(report.organization()).isEqualTo("empty-org");
                    assertThat(report.users()).isEmpty();
                })
                .verifyComplete();
    }
}
