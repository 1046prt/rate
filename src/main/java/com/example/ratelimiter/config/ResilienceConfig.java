package com.example.ratelimiter.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

/**
 * Configuration for Resilience4j circuit breaker and retry mechanisms.
 * Uses sensible defaults which can be overridden via application properties if needed.
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        CircuitBreakerConfig defaultConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50) // 50% failures trigger open state
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .slidingWindowSize(20)
                .permittedNumberOfCallsInHalfOpenState(5)
                .build();
        return CircuitBreakerRegistry.of(defaultConfig);
    }

    @Bean
    public RetryRegistry retryRegistry() {
        RetryConfig defaultRetryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(200))
                .build();
        return RetryRegistry.of(defaultRetryConfig);
    }
}
