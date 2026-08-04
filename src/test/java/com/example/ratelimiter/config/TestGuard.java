package com.example.ratelimiter.config;

import com.example.ratelimiter.resilience.RedisGuard;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Builds a {@link RedisGuard} whose circuit breaker effectively never opens, so ordinary
 * limiter tests exercise the happy path instead of the fail-open path (which has its own
 * dedicated test, {@code RedisDownFailOpenTest}).
 */
public final class TestGuard {

    private TestGuard() {
    }

    public static RedisGuard guard() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(100)
                .failureRateThreshold(100)
                .permittedNumberOfCallsInHalfOpenState(100)
                .build();
        return new RedisGuard(CircuitBreakerRegistry.of(config), new SimpleMeterRegistry());
    }
}
