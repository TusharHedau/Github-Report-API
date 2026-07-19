package com.github.report.exception;

import org.springframework.http.HttpStatusCode;

/**
 * Thrown when GitHub responds with HTTP 404, indicating the requested
 * organisation or repository does not exist (or the token lacks access).
 */
public class GithubNotFoundException extends GithubApiException {

    public GithubNotFoundException(String resource) {
        super("GitHub resource not found: " + resource,
                HttpStatusCode.valueOf(404));
    }
}
