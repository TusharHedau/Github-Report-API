package com.github.report.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses GitHub's paginated {@code Link} response header to extract the URL
 * of the next page.
 *
 * <h2>GitHub Link header format</h2>
 * GitHub follows <a href="https://tools.ietf.org/html/rfc5988">RFC 5988</a> Web Linking:
 * <pre>
 * Link: &lt;https://api.github.com/orgs/spring-projects/repos?page=2&amp;per_page=100&gt;; rel="next",
 *       &lt;https://api.github.com/orgs/spring-projects/repos?page=5&amp;per_page=100&gt;; rel="last"
 * </pre>
 *
 * <h2>Why a separate utility class?</h2>
 * <ul>
 *   <li>Single Responsibility — parsing is orthogonal to HTTP fetching.</li>
 *   <li>Testable in isolation without a Spring context or WebClient.</li>
 *   <li>Reusable by both the repository-fetch and collaborator-fetch pipelines.</li>
 * </ul>
 */
@Slf4j
@Component
public class LinkHeaderParser {

    /**
     * Compiled pattern that extracts the URL for {@code rel="next"} from a
     * GitHub {@code Link} header value.
     *
     * <p>Pattern breakdown:
     * <pre>
     *   &lt;        — literal opening angle bracket
     *   ([^&gt;]+)  — capture group: the URL (any char except '&gt;')
     *   &gt;        — literal closing angle bracket
     *   \\s*;\\s* — semicolon with optional surrounding whitespace
     *   rel="next" — literal rel type
     * </pre>
     *
     * The {@code CASE_INSENSITIVE} flag handles both {@code rel="next"} and
     * {@code rel="Next"} which some proxies may normalise differently.
     */
    private static final Pattern NEXT_LINK_PATTERN = Pattern.compile(
            "<([^>]+)>;\\s*rel=\"next\"",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Extracts the {@code rel="next"} URL from an HTTP {@link HttpHeaders} map.
     *
     * @param headers the response headers from a GitHub API call
     * @return an {@code Optional} containing the next-page URL, or
     *         {@code Optional.empty()} if this is the last page or the header is absent
     */
    public Optional<String> extractNextUrl(HttpHeaders headers) {
        String linkHeader = headers.getFirst(HttpHeaders.LINK);

        if (linkHeader == null || linkHeader.isBlank()) {
            return Optional.empty();
        }

        Matcher matcher = NEXT_LINK_PATTERN.matcher(linkHeader);
        if (matcher.find()) {
            String nextUrl = matcher.group(1);
            log.debug("Pagination: next page URL found → {}", nextUrl);
            return Optional.of(nextUrl);
        }

        log.debug("Pagination: no 'next' relation in Link header — last page reached.");
        return Optional.empty();
    }

    /**
     * Convenience overload that works directly with a raw header string value.
     *
     * <p>This is used in unit tests and in places where the full
     * {@link HttpHeaders} object is not available.
     *
     * @param linkHeaderValue raw value of the {@code Link} header
     * @return an {@code Optional} containing the next-page URL, or empty
     */
    public Optional<String> extractNextUrl(String linkHeaderValue) {
        if (linkHeaderValue == null || linkHeaderValue.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = NEXT_LINK_PATTERN.matcher(linkHeaderValue);
        return matcher.find()
                ? Optional.of(matcher.group(1))
                : Optional.empty();
    }
}
