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

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for TokenBucketLimiter using Testcontainers Redis.
 */
@SpringBootTest
@Import(TestRedisConfig.class)
public class TokenBucketLimiterTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    private TokenBucketLimiter limiter;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();
        RateLimiterProperties props = new RateLimiterProperties();
        props.setLimits(Map.of("TOKEN_BUCKET", 10L));
        props.setWindows(Map.of("TOKEN_BUCKET", Duration.ofMillis(500)));
        limiter = new TokenBucketLimiter(redisTemplate, props, TestGuard.guard());
    }

    @Test
    void allowWithinCapacity() {
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.allow("clientA"), "Request " + i + " should be allowed");
        }
        assertFalse(limiter.allow("clientA"), "11th request should be rejected");
    }

    @Test
    void tokensRefillAfterPeriodElapsed() throws InterruptedException {
        String clientId = "clientB";
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.allow(clientId));
        }
        assertFalse(limiter.allow(clientId), "bucket empty, must be rejected");

        // After 2 full refill periods the bucket is refilled to capacity
        Thread.sleep(1100);
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.allow(clientId), "Request after refill should be allowed");
        }
        assertFalse(limiter.allow(clientId));
    }

    @Test
    void remainingReportsTokens() {
        String clientId = "clientC";
        assertEquals(10, limiter.remaining(clientId));
        assertTrue(limiter.allow(clientId));
        assertEquals(9, limiter.remaining(clientId));
    }
}
