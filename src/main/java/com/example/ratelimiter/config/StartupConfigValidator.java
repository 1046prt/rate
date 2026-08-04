package com.example.ratelimiter.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Validates that critical configuration properties are present at application startup.
 * This helps fail fast in production if required environment variables are missing.
 */
@Component
public class StartupConfigValidator implements ApplicationRunner {

    private final RateLimiterProperties properties;

    public StartupConfigValidator(RateLimiterProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // Validate API key (optional – can be empty for open access)
        // If you want to enforce it, uncomment the block below.
        // if (!StringUtils.hasText(properties.getApiKey())) {
        //     throw new IllegalStateException("Missing required environment variable: RATELIMITER_API_KEY");
        // }

        // Validate that at least one limit is configured
        if (properties.getLimits() == null || properties.getLimits().isEmpty()) {
            throw new IllegalStateException("Rate limiter limits are not configured. Set RATELIMITER_LIMITS in environment.");
        }

        // Validate windows configuration
        if (properties.getWindows() == null || properties.getWindows().isEmpty()) {
            throw new IllegalStateException("Rate limiter windows are not configured. Set RATELIMITER_WINDOWS in environment.");
        }

        // Add other required environment variable checks here, e.g., Redis connection
        // String redisHost = System.getenv("REDIS_HOST");
        // if (!StringUtils.hasText(redisHost)) {
        //     throw new IllegalStateException("Missing required environment variable: REDIS_HOST");
        // }
    }
}
