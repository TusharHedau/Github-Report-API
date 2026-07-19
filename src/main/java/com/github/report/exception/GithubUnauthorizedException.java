package com.github.report.exception;

import org.springframework.http.HttpStatusCode;

/**
 * Thrown when GitHub responds with HTTP 401, indicating the PAT is missing,
 * expired, or does not have the required scopes.
 */
public class GithubUnauthorizedException extends GithubApiException {

    public GithubUnauthorizedException() {
        super("GitHub authentication failed. Check that GITHUB_TOKEN is set "
              + "and has the required scopes (repo, read:org).",
                HttpStatusCode.valueOf(401));
    }
}
