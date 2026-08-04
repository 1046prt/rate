package com.example.ratelimiter.algorithm;

import com.example.ratelimiter.config.RateLimiterProperties;
import com.example.ratelimiter.resilience.RedisGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Fixed Window rate limiter implementation.
 * Stores a counter per client in Redis that resets each window.
 * The INCR + EXPIRE pair is executed atomically via a Lua script so the
 * expiry can never be lost under concurrent access.
 */
@Component
public class FixedWindowLimiter implements RateLimiter {

    private static final String LUA_SCRIPT = """
            local count = redis.call('INCR', KEYS[1])
            if count == 1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return count
            """;

    private final StringRedisTemplate redisTemplate;
    private final long limit;
    private final Duration window;
    private final RedisScript<Long> script;
    private final RedisGuard guard;

    @Autowired
    public FixedWindowLimiter(StringRedisTemplate redisTemplate, RateLimiterProperties properties,
                              RedisGuard guard) {
        this.redisTemplate = redisTemplate;
        this.limit = properties.getLimits().getOrDefault("FIXED", properties.getDefaultLimit());
        this.window = properties.getWindows().getOrDefault("FIXED", Duration.ofSeconds(properties.getDefaultWindowSec()));
        this.script = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
        this.guard = guard;
    }

    @Override
    public boolean allow(String clientId) {
        Long count = guard.execute("fixed:allow",
                () -> redisTemplate.execute(script, List.of(key(clientId)), String.valueOf(window.toSeconds())));
        if (count == null) {
            return true; // Redis unavailable – fail open
        }
        return count <= limit;
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
        Long count = guard.execute("fixed:remaining",
                () -> {
                    String value = redisTemplate.opsForValue().get(key(clientId));
                    return value == null ? 0L : Long.parseLong(value);
                });
        return Math.max(0L, limit - (count == null ? 0L : count));
    }

    private String key(String clientId) {
        return "fixed:" + clientId;
    }
}
