package com.example.ratelimiter.resilience;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link RedisGuard}: successful calls pass through, failed calls fall back
 * to {@code null} (fail-open), and an open circuit short-circuits without invoking Redis.
 */
class RedisGuardTest {

    @Test
    void returnsResultOnSuccess() {
        RedisGuard guard = new RedisGuard(CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry());
        assertEquals(42L, guard.execute("op", () -> 42L));
    }

    @Test
    void returnsNullWhenCallFails() {
        RedisGuard guard = new RedisGuard(CircuitBreakerRegistry.ofDefaults(), new SimpleMeterRegistry());
        assertNull(guard.execute("op", () -> {
            throw new IllegalStateException("redis down");
        }));
    }

    @Test
    void opensCircuitAndShortCircuitsWithoutInvokingRedis() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(1))
                .build();
        RedisGuard guard = new RedisGuard(CircuitBreakerRegistry.of(config), meters);

        for (int i = 0; i < 4; i++) {
            assertNull(guard.execute("op", () -> {
                throw new IllegalStateException("boom");
            }));
        }

        AtomicBoolean invoked = new AtomicBoolean(false);
        Long result = guard.execute("op", () -> {
            invoked.set(true);
            return 1L;
        });
        assertNull(result, "open circuit must fail open without touching Redis");
        assertFalse(invoked.get(), "supplier must not be invoked while circuit is open");
        assertEquals(5.0, meters.counter("ratelimiter.redis.fallback").count());
    }

    @Test
    void recoversToSuccessWhenCircuitCloses() throws InterruptedException {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(4)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMillis(200))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build();
        RedisGuard guard = new RedisGuard(CircuitBreakerRegistry.of(config), meters);

        for (int i = 0; i < 4; i++) {
            guard.execute("op", () -> {
                throw new IllegalStateException("boom");
            });
        }

        Thread.sleep(300); // let the circuit move to half-open
        AtomicInteger calls = new AtomicInteger();
        Long result = guard.execute("op", () -> {
            calls.incrementAndGet();
            return 7L;
        });
        assertEquals(7L, result, "half-open circuit should probe Redis again and recover");
        assertEquals(1, calls.get());
    }
}
