package com.github.report.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration class that sets up the OpenAPI 3.0 / Swagger documentation.
 *
 * <p>Exposes metadata about the API at {@code /swagger-ui.html} (UI) and
 * {@code /v3/api-docs} (JSON schema), allowing integration tools or clients to inspect
 * our endpoints, security requirements, and model schemas.
 */
@Configuration
public class OpenApiConfig {

    /**
     * Configures global OpenAPI metadata for the application.
     *
     * @return the configured {@link OpenAPI} object
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("GitHub Repository Access Report API")
                        .version("1.0.0")
                        .description("""
                                A reactive, non-blocking Spring Boot 3 REST API that connects to GitHub
                                and aggregates repository access permission details into a pivoted, user-centric report.
                                
                                Features:
                                - Fully reactive and concurrent calls utilizing WebClient + Reactor.
                                - Link-header pagination handling.
                                - Robust rate-limit-aware retries with exponential backoff.
                                - Dynamic in-memory Caffeine cache layer.
                                """)
                        .contact(new Contact()
                                .name("Advanced Agentic Coding Team")
                                .email("support@example.com"))
                        .license(new License()
                                .name("Apache 2.0")
                                .url("https://www.apache.org/licenses/LICENSE-2.0")));
    }
}
