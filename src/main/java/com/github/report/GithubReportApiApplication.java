package com.github.report;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point for the GitHub Report API application.
 *
 * <p>Annotations:
 * <ul>
 *   <li>{@code @SpringBootApplication} — enables auto-configuration, component scan,
 *       and configuration properties scanning from this package downwards.</li>
 *   <li>{@code @EnableCaching} — activates Spring's proxy-based caching abstraction;
 *       Caffeine is wired in via {@code CacheConfig}.</li>
 *   <li>{@code @EnableAsync} — allows {@code @Async} methods and is required for
 *       CompletableFuture-based parallel GitHub API calls in the service layer.</li>
 * </ul>
 */
@SpringBootApplication
@EnableCaching
@EnableAsync
public class GithubReportApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(GithubReportApiApplication.class, args);
    }
}
