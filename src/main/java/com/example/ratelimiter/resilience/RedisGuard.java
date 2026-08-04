package com.example.ratelimiter.resilience;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Guards every Redis operation with a Resilience4j circuit breaker and a fail-open policy.
 *
 * <p>The rate limiter is not allowed to become a hard dependency of the service: if Redis is
 * unreachable, a circuit open, or an individual command fails (e.g. the client-side command
 * timeout in {@code spring.data.redis.timeout} fires), the guard returns {@code null} instead of
 * throwing. Limiters treat a {@code null} result as "allow the request" (fail-open) and log the
 * outage, so the API stays up during a Redis outage instead of failing every request with 500s.
 *
 * <p>Each fallback is counted in {@code ratelimiter.redis.fallback} so outages are visible in
 * Prometheus and can be alerted on.
 */
@Component
public class RedisGuard {

    private static final Logger log = LoggerFactory.getLogger(RedisGuard.class);

    private final CircuitBreaker circuitBreaker;
    private final Counter fallbackCounter;

    public RedisGuard(CircuitBreakerRegistry registry, MeterRegistry meterRegistry) {
        this.circuitBreaker = registry.circuitBreaker("redis");
        this.fallbackCounter = Counter.builder("ratelimiter.redis.fallback")
                .description("Redis calls that failed and fell back to fail-open (request allowed)")
                .register(meterRegistry);
        // Bind breaker state/calls/failure-rate meters (resilience4j_circuitbreaker_*)
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
    }

    /**
     * Executes a Redis call under circuit-breaker protection. Returns the call's result, or
     * {@code null} when the circuit is open or the call failed (fail-open).
     */
    public <T> T execute(String operation, Supplier<T> redisCall) {
        if (!circuitBreaker.tryAcquirePermission()) {
            fallbackCounter.increment();
            log.warn("Redis circuit breaker is open for '{}' - failing open (request allowed)", operation);
            return null;
        }
        long start = System.nanoTime();
        try {
            T result = redisCall.get();
            circuitBreaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            return result;
        } catch (Exception e) {
            circuitBreaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e);
            fallbackCounter.increment();
            log.warn("Redis call '{}' failed - failing open (request allowed): {}", operation, e.getMessage());
            return null;
        }
    }
}
