package com.example.ratelimiter.algorithm;

import com.example.ratelimiter.config.RateLimiterProperties;
import com.example.ratelimiter.resilience.RedisGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Sliding Window Log limiter implementation using a Redis Sorted Set.
 * Stores timestamps of requests and evicts entries older than the window.
 * Trim, check and add are executed atomically in a single Lua script, so
 * concurrent requests cannot over-admit.
 */
@Component
public class SlidingWindowLogLimiter implements RateLimiter {

    private static final String LUA_SCRIPT = """
            local now = tonumber(ARGV[1])
            local windowStart = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local ttl = tonumber(ARGV[4])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, windowStart)
            local count = redis.call('ZCARD', KEYS[1])
            if count < limit then
              redis.call('ZADD', KEYS[1], now, ARGV[5])
              redis.call('EXPIRE', KEYS[1], ttl)
              return count + 1
            end
            return -1
            """;

    private final StringRedisTemplate redisTemplate;
    private final long limit;
    private final Duration window;
    private final RedisScript<Long> script;
    private final RedisGuard guard;

    @Autowired
    public SlidingWindowLogLimiter(StringRedisTemplate redisTemplate, RateLimiterProperties properties,
                                   RedisGuard guard) {
        this.redisTemplate = redisTemplate;
        this.limit = properties.getLimits().getOrDefault("SLIDING_LOG", properties.getDefaultLimit());
        this.window = properties.getWindows().getOrDefault("SLIDING_LOG", Duration.ofSeconds(properties.getDefaultWindowSec()));
        this.script = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
        this.guard = guard;
    }

    @Override
    public boolean allow(String clientId) {
        long now = Instant.now().toEpochMilli();
        long windowStart = now - window.toMillis();
        // Member must be unique per request or concurrent/same-ms entries would collide
        String member = now + ":" + UUID.randomUUID();
        Long count = guard.execute("slidinglog:allow",
                () -> redisTemplate.execute(script, List.of(key(clientId)),
                        String.valueOf(now), String.valueOf(windowStart), String.valueOf(limit),
                        String.valueOf(window.toSeconds()), member));
        if (count == null) {
            return true; // Redis unavailable – fail open
        }
        return count >= 0;
    }

    @Override
    public long getLimit() {
        return limit;
    }

    @Override
    public long getWindowSeconds() {
        return window.toSeconds();
    }

    @Override
    public long remaining(String clientId) {
        Long size = guard.execute("slidinglog:remaining",
                () -> redisTemplate.opsForZSet().size(key(clientId)));
        long count = size == null ? 0L : size;
        return Math.max(0L, limit - count);
    }

    private String key(String clientId) {
        return "slidinglog:" + clientId;
    }
}
