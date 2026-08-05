package com.example.ratelimiter.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisHealthIndicator}: UP with latency when Redis responds,
 * DOWN with the cause when the ping fails.
 */
class RedisHealthIndicatorTest {

    private StringRedisTemplate templateWithPing(Object result) {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.ping()).thenReturn((String) result);
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.getConnectionFactory()).thenReturn(factory);
        return template;
    }

    @Test
    void reportsUpWhenRedisPings() {
        RedisHealthIndicator indicator = new RedisHealthIndicator(templateWithPing("PONG"));

        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertNotNull(health.getDetails().get("latencyMs"));
    }

    @Test
    void reportsDownWhenRedisPingFails() {
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.ping()).thenThrow(new RuntimeException("connection refused"));
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenReturn(connection);
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        when(template.getConnectionFactory()).thenReturn(factory);
        RedisHealthIndicator indicator = new RedisHealthIndicator(template);

        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertTrue(health.getDetails().containsKey("latencyMs"));
    }
}
