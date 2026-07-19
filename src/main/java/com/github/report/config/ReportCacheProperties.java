package com.github.report.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Strongly-typed configuration properties bound from the {@code report.cache.*}
 * namespace in {@code application.yml}.
 *
 * <p>Decoupling cache settings into their own properties class (rather than folding
 * them into {@link GithubProperties}) follows the Single Responsibility Principle —
 * GitHub connectivity concerns are separate from report caching concerns.
 *
 * @param ttlMinutes How long (minutes) a generated report is kept in the cache
 * @param maxSize    Maximum number of distinct organisation reports in memory
 */
@ConfigurationProperties(prefix = "report.cache")
public record ReportCacheProperties(
        int ttlMinutes,
        int maxSize
) {}
