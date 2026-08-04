package com.example.ratelimiter.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configures Micrometer metrics for the rate limiter service.
 * Defines counters for total requests, allowed requests, and rejected requests.
 */
@Configuration
public class MetricsConfig {

    @Bean
    public Counter totalCounter(MeterRegistry registry) {
        return Counter.builder("ratelimiter.requests.total")
                .description("Total number of rate limit check requests")
                .register(registry);
    }

    @Bean
    public Counter allowedCounter(MeterRegistry registry) {
        return Counter.builder("ratelimiter.requests.allowed")
                .description("Number of requests allowed by the rate limiter")
                .register(registry);
    }

    @Bean
    public Counter rejectedCounter(MeterRegistry registry) {
        return Counter.builder("ratelimiter.requests.rejected")
                .description("Number of requests rejected by the rate limiter")
                .register(registry);
    }

    @Bean
    public Counter invalidApiKeyCounter(MeterRegistry registry) {
        return Counter.builder("ratelimiter.auth.invalid_api_key")
                .description("Number of requests with invalid API key")
                .register(registry);
    }
}
