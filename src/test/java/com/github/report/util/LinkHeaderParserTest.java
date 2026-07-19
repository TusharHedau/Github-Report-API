package com.github.report.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link LinkHeaderParser}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Standard multi-relation Link header (next + last)</li>
 *   <li>Single-relation Link header (only last — final page)</li>
 *   <li>Missing / blank / null header values</li>
 *   <li>Case-insensitive rel matching</li>
 *   <li>Convenience {@link HttpHeaders} overload</li>
 * </ul>
 *
 * <p>No Spring context is needed — {@link LinkHeaderParser} is a plain POJO here.
 */
@DisplayName("LinkHeaderParser")
class LinkHeaderParserTest {

    private final LinkHeaderParser parser = new LinkHeaderParser();

    // ── Standard multi-rel header ────────────────────────────────────────────────

    @Test
    @DisplayName("extracts next URL from standard GitHub Link header")
    void extractNextUrl_standardHeader_returnsNextUrl() {
        String header = "<https://api.github.com/orgs/acme/repos?page=2&per_page=100>; rel=\"next\", "
                      + "<https://api.github.com/orgs/acme/repos?page=5&per_page=100>; rel=\"last\"";

        Optional<String> result = parser.extractNextUrl(header);

        assertThat(result)
                .isPresent()
                .hasValue("https://api.github.com/orgs/acme/repos?page=2&per_page=100");
    }

    @Test
    @DisplayName("returns empty when only rel=last is present (final page)")
    void extractNextUrl_onlyLastRel_returnsEmpty() {
        String header = "<https://api.github.com/orgs/acme/repos?page=5&per_page=100>; rel=\"last\"";

        Optional<String> result = parser.extractNextUrl(header);

        assertThat(result).isEmpty();
    }

    // ── Blank / null ─────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "header = [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("returns empty when header is null, empty, or blank")
    void extractNextUrl_blankOrNull_returnsEmpty(String header) {
        assertThat(parser.extractNextUrl(header)).isEmpty();
    }

    // ── Case-insensitive matching ─────────────────────────────────────────────────

    @Test
    @DisplayName("matches rel=Next (capital N) case-insensitively")
    void extractNextUrl_caseInsensitive_returnsNextUrl() {
        String header = "<https://api.github.com/orgs/acme/repos?page=2>; rel=\"Next\"";

        assertThat(parser.extractNextUrl(header)).isPresent();
    }

    // ── First page is also the last page ─────────────────────────────────────────

    @Test
    @DisplayName("returns empty when there is only one page (no Link header)")
    void extractNextUrl_noLinkHeader_returnsEmpty() {
        HttpHeaders headers = new HttpHeaders();
        // No Link header added — single-page result

        assertThat(parser.extractNextUrl(headers)).isEmpty();
    }

    // ── HttpHeaders overload ─────────────────────────────────────────────────────

    @Test
    @DisplayName("extracts next URL from HttpHeaders overload")
    void extractNextUrl_httpHeaders_returnsNextUrl() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.LINK,
                "<https://api.github.com/repos/acme/repo1/collaborators?page=2>; rel=\"next\"");

        Optional<String> result = parser.extractNextUrl(headers);

        assertThat(result)
                .isPresent()
                .hasValue("https://api.github.com/repos/acme/repo1/collaborators?page=2");
    }

    // ── URL with special characters ───────────────────────────────────────────────

    @Test
    @DisplayName("handles URLs with multiple query parameters correctly")
    void extractNextUrl_complexUrl_parsedCorrectly() {
        String header =
                "<https://api.github.com/orgs/acme/repos?page=3&per_page=100&type=all&sort=full_name>;"
                + " rel=\"next\", "
                + "<https://api.github.com/orgs/acme/repos?page=10&per_page=100&type=all&sort=full_name>;"
                + " rel=\"last\"";

        Optional<String> result = parser.extractNextUrl(header);

        assertThat(result)
                .isPresent()
                .hasValue("https://api.github.com/orgs/acme/repos?page=3&per_page=100&type=all&sort=full_name");
    }
}
