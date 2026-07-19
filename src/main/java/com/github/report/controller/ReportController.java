package com.github.report.controller;

import com.github.report.dto.OrganizationReport;
import com.github.report.exception.ErrorResponse;
import com.github.report.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST Controller that exposes endpoints for generating repository access reports.
 *
 * <p>Uses Spring WebFlux (reactive REST controller) to return {@link Mono}
 * publishers, ensuring non-blocking execution throughout the entire stack.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
@Tag(name = "Report Controller", description = "Endpoints for generating GitHub organization repository access permission reports")
public class ReportController {

    private final ReportService reportService;

    /**
     * Generates a repository access report showing which users have access to which
     * repositories in the given GitHub organization.
     *
     * <p>HTTP GET Endpoint: {@code GET /api/report/{organization}}
     *
     * @param organization the GitHub organization name/slug
     * @return a {@link Mono} containing the {@link OrganizationReport}
     */
    @GetMapping("/{organization}")
    @Operation(
            summary = "Generate repository access report for an organization",
            description = "Pivots the GitHub repository-collaborator map to return a user-centric report indicating "
                    + "which users have read/write access to which repositories within the organization. Results are sorted alphabetically.",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "Successfully generated report",
                            content = @Content(schema = @Schema(implementation = OrganizationReport.class))
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "Invalid organization name supplied",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "GitHub Personal Access Token is invalid or expired",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "Organization not found on GitHub",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "429",
                            description = "GitHub API rate limit exceeded",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    ),
                    @ApiResponse(
                            responseCode = "504",
                            description = "Request to GitHub timed out",
                            content = @Content(schema = @Schema(implementation = ErrorResponse.class))
                    )
            }
    )
    public Mono<OrganizationReport> getOrganizationReport(
            @PathVariable("organization")
            @Parameter(
                    description = "GitHub organization name/slug (only alphanumeric, hyphens, and underscores allowed)",
                    example = "spring-projects",
                    required = true
            )
            @NotBlank(message = "Organization name must not be blank")
            @Pattern(regexp = "^[a-zA-Z0-9-_]+$",
                     message = "Organization name can only contain alphanumeric characters, hyphens, and underscores")
            String organization) {

        log.info("REST API Request: Generate access report for org '{}'", organization);

        return reportService.generateReport(organization);
    }
}
