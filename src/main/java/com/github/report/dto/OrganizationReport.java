package com.github.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Data Transfer Object representing the final generated report for a GitHub organization.
 *
 * <p>Matches the required JSON structure:
 * <pre>
 * {
 *   "organization": "spring-projects",
 *   "users": [
 *     { "username": "alice", "repositories": ["repo1", "repo2"] },
 *     { "username": "bob", "repositories": ["repo3"] }
 *   ]
 * }
 * </pre>
 *
 * @param organization the name/slug of the organization
 * @param users        the list of users and the repositories they have access to
 */
@Schema(description = "Aggregated access report for a GitHub organization")
public record OrganizationReport(
        @Schema(description = "The organization name", example = "spring-projects")
        String organization,

        @Schema(description = "List of collaborators and their accessible repositories")
        List<UserReport> users
) {}
