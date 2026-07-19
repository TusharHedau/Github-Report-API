package com.github.report;

import com.github.report.config.GithubProperties;
import com.github.report.config.ReportCacheProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Root configuration class that registers all {@code @ConfigurationProperties}
 * beans in one central place.
 *
 * <p>Declaring {@code @EnableConfigurationProperties} here (rather than on each
 * record individually) keeps the records themselves free of Spring annotations
 * and makes them usable as plain Java objects in tests without a Spring context.
 */
@Configuration
@EnableConfigurationProperties({
        GithubProperties.class,
        ReportCacheProperties.class
})
public class AppConfig {
    // Intentionally empty — this class exists solely to activate property binding.
}
