package com.github.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * Immutable domain model representing a single GitHub collaborator as returned by
 * {@code GET /repos/{owner}/{repo}/collaborators}.
 *
 * <p>Only the fields relevant to report generation are mapped; all other fields
 * from the API response are silently ignored.
 *
 * @param id       Unique numeric identifier for the GitHub user
 * @param login    GitHub username (e.g. {@code "alice"})
 * @param type     Account type: {@code "User"} or {@code "Bot"}
 * @param siteAdmin Whether the user is a GitHub site administrator
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Builder
public record GithubCollaborator(

        long id,

        String login,

        String type,

        @JsonProperty("site_admin")
        boolean siteAdmin
) {}
