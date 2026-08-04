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

/**
 * Token Bucket rate limiter using Redis.
 * Stores remaining tokens and the last refill timestamp, refilling the bucket
 * lazily on each request. Refill + consumption happen atomically in a Lua script.
 */
@Component
public class TokenBucketLimiter implements RateLimiter {

    private static final String LUA_SCRIPT = """
            local now = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local periodMs = tonumber(ARGV[3])
            local ttlMs = tonumber(ARGV[4])
            local value = redis.call('GET', KEYS[1])
            local tokens, lastRefill
            if value then
              local idx = string.find(value, ':')
              tokens = tonumber(string.sub(value, 1, idx - 1))
              lastRefill = tonumber(string.sub(value, idx + 1))
              local periods = math.floor((now - lastRefill) / periodMs)
              if periods > 0 then
                tokens = math.min(capacity, tokens + periods * capacity)
                lastRefill = lastRefill + periods * periodMs
              end
            else
              tokens = capacity
              lastRefill = now
            end
            if tokens < 1 then
              return -1
            end
            tokens = tokens - 1
            redis.call('SET', KEYS[1], tokens .. ':' .. lastRefill, 'PX', ttlMs)
            return tokens
            """;

    private final StringRedisTemplate redisTemplate;
    private final long capacity;
    private final Duration refillPeriod;
    private final RedisScript<Long> script;
    private final RedisGuard guard;

    @Autowired
    public TokenBucketLimiter(StringRedisTemplate redisTemplate, RateLimiterProperties properties,
                              RedisGuard guard) {
        this.redisTemplate = redisTemplate;
        this.capacity = properties.getLimits().getOrDefault("TOKEN_BUCKET", properties.getDefaultLimit());
        this.refillPeriod = properties.getWindows().getOrDefault("TOKEN_BUCKET", Duration.ofSeconds(properties.getDefaultWindowSec()));
        this.script = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
        this.guard = guard;
    }

    @Override
    public boolean allow(String clientId) {
        long now = Instant.now().toEpochMilli();
        Long tokens = guard.execute("tokenbucket:allow",
                () -> redisTemplate.execute(script, List.of(key(clientId)),
                        String.valueOf(now), String.valueOf(capacity), String.valueOf(refillPeriod.toMillis()),
                        String.valueOf(refillPeriod.multipliedBy(2).toMillis())));
        if (tokens == null) {
            return true; // Redis unavailable – fail open
        }
        return tokens >= 0;
    }

    @Override
    public long getLimit() {
        return capacity;
    }

    @Override
    public long getWindowSeconds() {
        return refillPeriod.toSeconds();
    }

    @Override
    public long remaining(String clientId) {
        Long tokens = guard.execute("tokenbucket:remaining", () -> {
            String value = redisTemplate.opsForValue().get(key(clientId));
            if (value == null) {
                return capacity;
            }
            String[] parts = value.split(":");
            long tokensNow = Long.parseLong(parts[0]);
            long lastRefill = Long.parseLong(parts[1]);
            long now = Instant.now().toEpochMilli();
            long periods = (now - lastRefill) / refillPeriod.toMillis();
            if (periods > 0) {
                tokensNow = Math.min(capacity, tokensNow + periods * capacity);
            }
            return Math.max(0L, Math.min(capacity, tokensNow));
        });
        return tokens == null ? capacity : tokens;
    }

    private String key(String clientId) {
        return "tokenbucket:" + clientId;
    }
}
