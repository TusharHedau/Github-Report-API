package com.github.report.service;

import com.github.report.config.CacheConfig;
import com.github.report.dto.OrganizationReport;
import com.github.report.model.GithubRepo;
import com.github.report.model.RepoCollaborators;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Integration test that verifies the caching behavior of {@link ReportService#generateReport(String)}.
 *
 * <p>Uses {@code @SpringBootTest} and {@code @ActiveProfiles("test")} to load the complete Spring context,
 * allowing us to verify that the Spring AOP caching proxy is intercepting calls and interacting
 * with Caffeine correctly.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("ReportService Caching")
class ReportServiceCachingTest {

    @Autowired
    private ReportService reportService;

    @MockBean
    private GithubService githubService;

    @Autowired
    private CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        // Ensure cache is completely empty before each test run
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.CACHE_ORG_REPORTS)).clear();
    }

    @Test
    @DisplayName("caches generated report so subsequent calls do not invoke GithubService")
    void generateReport_cachesResponse() {
        GithubRepo repo = GithubRepo.builder()
                .id(1L)
                .name("cached-repo")
                .owner(new GithubRepo.Owner("cached-org"))
                .build();
        RepoCollaborators rc = new RepoCollaborators(repo, List.of());

        // Stub the mock service to return a simple list containing one repo
        when(githubService.fetchReposWithCollaborators("cached-org"))
                .thenReturn(Flux.just(rc));

        // 1. First execution (Cache Miss): Should invoke the mocked service
        OrganizationReport report1 = reportService.generateReport("cached-org").block();
        assertThat(report1).isNotNull();
        assertThat(report1.organization()).isEqualTo("cached-org");

        // 2. Second execution (Cache Hit): Should bypass the mocked service and return cached value
        OrganizationReport report2 = reportService.generateReport("cached-org").block();
        assertThat(report2).isNotNull();
        assertThat(report2.organization()).isEqualTo("cached-org");

        // 3. Both responses should contain identical data
        assertThat(report1).isEqualTo(report2);

        // 4. Verify that the mocked GithubService was only invoked EXACTLY ONCE
        verify(githubService, times(1)).fetchReposWithCollaborators("cached-org");
    }

    @Test
    @DisplayName("evicts cache on explicit cache clear (verifies cache manager operations)")
    void cacheManager_evictsCorrectly() {
        GithubRepo repo = GithubRepo.builder()
                .id(1L)
                .name("cached-repo")
                .owner(new GithubRepo.Owner("cached-org"))
                .build();
        RepoCollaborators rc = new RepoCollaborators(repo, List.of());

        when(githubService.fetchReposWithCollaborators("cached-org"))
                .thenReturn(Flux.just(rc));

        // First call (Cache Miss)
        reportService.generateReport("cached-org").block();

        // Evict cache manually via manager
        Objects.requireNonNull(cacheManager.getCache(CacheConfig.CACHE_ORG_REPORTS)).evict("cached-org");

        // Second call (Cache Miss again, because it was evicted)
        reportService.generateReport("cached-org").block();

        // Verify githubService was invoked twice
        verify(githubService, times(2)).fetchReposWithCollaborators("cached-org");
    }
}
