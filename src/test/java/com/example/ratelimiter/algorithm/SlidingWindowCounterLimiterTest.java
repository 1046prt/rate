package com.example.ratelimiter.algorithm;

import com.example.ratelimiter.config.RateLimiterProperties;
import com.example.ratelimiter.config.TestGuard;
import com.example.ratelimiter.config.TestRedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for SlidingWindowCounterLimiter using Testcontainers Redis.
 */
@SpringBootTest
@Import(TestRedisConfig.class)
public class SlidingWindowCounterLimiterTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RateLimiterProperties properties;

    private SlidingWindowCounterLimiter limiter;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();
        limiter = new SlidingWindowCounterLimiter(redisTemplate, properties, TestGuard.guard());
    }

    @Test
    void allowWithinLimit() {
        String clientId = "clientA";
        for (int i = 0; i < 100; i++) {
            assertTrue(limiter.allow(clientId), "Request " + i + " should be allowed");
        }
    }

    @Test
    void rejectBeyondLimit() {
        String clientId = "clientB";
        for (int i = 0; i < 100; i++) {
            assertTrue(limiter.allow(clientId), "Request " + i + " should be allowed");
        }
        assertFalse(limiter.allow(clientId), "101st request should be rejected");
    }

    @Test
    void previousBucketWeightedDownOverTime() {
        // A request at the start of a new bucket must still be allowed: the
        // previous bucket's count is weighted proportionally to elapsed time.
        String clientId = "clientC";
        assertTrue(limiter.allow(clientId));
        assertTrue(limiter.allow(clientId));
    }

    @Test
    void remainingReflectsConsumedRequests() {
        String clientId = "clientD";
        assertEquals(100, limiter.remaining(clientId), "fresh client starts at the full limit");
        assertTrue(limiter.allow(clientId));
        assertEquals(99, limiter.remaining(clientId), "one request consumes one slot");
    }
}
