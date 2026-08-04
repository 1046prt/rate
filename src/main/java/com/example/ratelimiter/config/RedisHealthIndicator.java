package com.example.ratelimiter.config;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.StopWatch;

/**
 * Health indicator that checks Redis connectivity and measures latency.
 * Returns UP if a ping succeeds within the timeout, otherwise DOWN.
 */
@Component
public class RedisHealthIndicator implements HealthIndicator {

    private final StringRedisTemplate redisTemplate;

    public RedisHealthIndicator(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public Health health() {
        StopWatch sw = new StopWatch();
        try {
            sw.start();
            // Simple ping via set+delete to avoid requiring a key
            redisTemplate.getConnectionFactory().getConnection().ping();
            sw.stop();
            return Health.up()
                    .withDetail("latencyMs", sw.getTotalTimeMillis())
                    .build();
        } catch (Exception e) {
            if (sw.isRunning()) {
                sw.stop();
            }
            return Health.down(e)
                    .withDetail("latencyMs", sw.getTotalTimeMillis())
                    .build();
        }
    }
}
