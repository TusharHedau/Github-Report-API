package com.github.report.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Configuration class that sets up the Caffeine cache provider.
 *
 * <h2>Caching Strategy</h2>
 * <p>Generating reports requires making dozens or hundreds of parallel HTTP requests
 * to the GitHub API, which is slow and consumes our rate-limit quota. To prevent this,
 * we cache the final generated report responses.
 *
 * <p>Since our report endpoints return reactive publishers ({@link reactor.core.publisher.Mono}),
 * Spring Cache abstraction natively intercepts the subscription and caches the *emitted*
 * value, not the publisher wrapper. Subsequent subscriptions for the same key resolve
 * instantly from the Caffeine cache.
 *
 * <h2>Configuration Knobs</h2>
 * <ul>
 *   <li><b>Cache Name:</b> {@code "organization-reports"}</li>
 *   <li><b>TTL (Time To Live):</b> read from {@code report.cache.ttl-minutes} in {@code application.yml} (default: 5 mins)</li>
 *   <li><b>Maximum Size:</b> read from {@code report.cache.max-size} in {@code application.yml} (default: 50 reports)</li>
 * </ul>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CacheConfig {

    public static final String CACHE_ORG_REPORTS = "organization-reports";

    private final ReportCacheProperties cacheProperties;

    /**
     * Builds the Caffeine-backed {@link CacheManager}.
     *
     * <p>{@code recordStats()} is enabled so cache hit/miss statistics can be
     * exposed via Spring Actuator endpoints (e.g. {@code /actuator/metrics}).
     *
     * @return the configured Caffeine {@code CacheManager}
     */
    @Bean
    public CacheManager cacheManager() {
        log.info("Configuring Caffeine Cache Manager [Name: {}, TTL: {} mins, MaxSize: {}]",
                CACHE_ORG_REPORTS, cacheProperties.ttlMinutes(), cacheProperties.maxSize());

        CaffeineCacheManager cacheManager = new CaffeineCacheManager(CACHE_ORG_REPORTS);
        // Enable AsyncCacheMode so that Mono/Flux reactive return types can be cached natively.
        // Spring Cache will cache the emitted value asynchronously when it completes.
        cacheManager.setAsyncCacheMode(true);
        
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(cacheProperties.ttlMinutes(), TimeUnit.MINUTES)
                .maximumSize(cacheProperties.maxSize())
                .recordStats());

        return cacheManager;
    }
}
