package com.github.report.controller;

import com.github.report.dto.OrganizationReport;
import com.github.report.dto.UserReport;
import com.github.report.service.ReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ReportController}.
 *
 * <p>Uses {@code @WebFluxTest(ReportController.class)} to execute a sliced integration test
 * focusing exclusively on the web layer. Auto-configures {@link WebTestClient} which we use to
 * simulate HTTP requests and assert headers, status codes, and JSON response bodies.
 */
@WebFluxTest(controllers = ReportController.class)
@DisplayName("ReportController")
class ReportControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockBean
    private ReportService reportService;

    @Test
    @DisplayName("returns 200 OK and correct JSON payload for valid organization name")
    void getOrganizationReport_validOrg_returnsReport() {
        OrganizationReport report = new OrganizationReport(
                "acme-org",
                List.of(
                        new UserReport("alice", List.of("repo-a", "repo-b")),
                        new UserReport("bob", List.of("repo-a"))
                )
        );

        when(reportService.generateReport("acme-org"))
                .thenReturn(Mono.just(report));

        webTestClient.get()
                .uri("/api/report/acme-org")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.organization").isEqualTo("acme-org")
                .jsonPath("$.users[0].username").isEqualTo("alice")
                .jsonPath("$.users[0].repositories[0]").isEqualTo("repo-a")
                .jsonPath("$.users[0].repositories[1]").isEqualTo("repo-b")
                .jsonPath("$.users[1].username").isEqualTo("bob")
                .jsonPath("$.users[1].repositories[0]").isEqualTo("repo-a");

        verify(reportService, times(1)).generateReport("acme-org");
    }

    @Test
    @DisplayName("returns 400 Bad Request if organization name contains invalid characters")
    void getOrganizationReport_invalidOrgName_returns400() {
        // "invalid*name" contains asterisk which violates ^[a-zA-Z0-9-_]+$
        // This is caught by MethodValidationPostProcessor and mapped to 400 Bad Request
        // by GlobalExceptionHandler.
        webTestClient.get()
                .uri("/api/report/invalid*name")
                .exchange()
                .expectStatus().isBadRequest();

        verifyNoInteractions(reportService);
    }
}
