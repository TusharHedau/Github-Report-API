package com.github.report.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Data Transfer Object representing a single user's repository access details.
 *
 * @param username     the login username of the GitHub collaborator
 * @param repositories the list of repository names the user has access to
 */
@Schema(description = "Access details for a single GitHub collaborator")
public record UserReport(
        @Schema(description = "GitHub login username", example = "alice")
        String username,

        @Schema(description = "List of repository names the user has access to", example = "[\"spring-framework\", \"spring-boot\"]")
        List<String> repositories
) {}
