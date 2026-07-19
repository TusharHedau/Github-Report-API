package com.github.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

/**
 * Immutable domain model representing a single GitHub repository as returned by
 * {@code GET /orgs/{org}/repos}.
 *
 * <p>Uses a Java 21 record for conciseness and immutability. The record is annotated
 * with Jackson annotations to handle the snake_case → camelCase mapping from the
 * GitHub API JSON response.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} ensures forward-compatibility:
 * if GitHub adds new fields in future API versions, deserialization will not fail.
 *
 * @param id       Unique numeric identifier for the repository
 * @param name     Short repository name (e.g. {@code "spring-framework"})
 * @param fullName Owner-qualified name (e.g. {@code "spring-projects/spring-framework"})
 * @param owner    The organisation or user who owns this repository
 * @param isPrivate Whether the repository is private ({@code true}) or public
 * @param fork     Whether this repository is a fork of another repository
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Builder
public record GithubRepo(

        long id,

        String name,

        @JsonProperty("full_name")
        String fullName,

        Owner owner,

        @JsonProperty("private")
        boolean isPrivate,

        boolean fork
) {

    /**
     * Nested record representing the repository owner (org or user).
     *
     * @param login The GitHub username / organisation slug
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Owner(
            @JsonProperty("login") String login
    ) {}
}
