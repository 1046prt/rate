package com.example.ratelimiter.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.Components;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI (Swagger UI) configuration.
 *
 * <p>Interactive docs: {@code /swagger-ui.html}; raw spec: {@code /v3/api-docs}.
 * The {@code X-Api-Key} scheme is documented so clients can send it directly from
 * the Swagger UI; it is only enforced when {@code RATELIMITER_API_KEY} is set.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Rate Limiter Service API")
                        .version("1.0.0")
                        .description("""
                                Distributed rate-limiting as a service. Every request carries a \
                                client identifier and an algorithm choice; the service decides whether \
                                the request is allowed or must be throttled (HTTP 429).

                                - Authentication: send `X-Api-Key` (required when `RATELIMITER_API_KEY` is configured).
                                - Rate-limit state lives in Redis and is shared across all replicas.
                                - Allowed and rejected responses carry standard rate-limit headers.
                                """)
                        .license(new License().name("MIT")))
                .components(new Components()
                        .addSecuritySchemes("apiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Api-Key")
                                .description("API key (set via RATELIMITER_API_KEY). Open access when not configured.")));
    }
}
