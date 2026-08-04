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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for FixedWindowLimiter using a real Redis instance via Testcontainers.
 */
@SpringBootTest
@Import(TestRedisConfig.class)
public class FixedWindowLimiterTest {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RateLimiterProperties properties;

    private FixedWindowLimiter limiter;

    @BeforeEach
    void setUp() {
        // Ensure a clean state before each test
        redisTemplate.getConnectionFactory().getConnection().flushAll();
        limiter = new FixedWindowLimiter(redisTemplate, properties, TestGuard.guard());
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
        // 101st request must be rejected
        assertFalse(limiter.allow(clientId), "101st request should be rejected");
    }

    @Test
    void windowResetsAfterExpiry() throws InterruptedException {
        String clientId = "clientC";
        assertTrue(limiter.allow(clientId));
        // Simulate window expiry (configured window is 60s) by removing the key
        redisTemplate.delete("fixed:" + clientId);
        assertTrue(limiter.allow(clientId), "Request after window expiry should be allowed");
    }
}
