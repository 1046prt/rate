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
 * Leaky Bucket rate limiter using Redis.
 * Tracks the amount of "water" (in-flight requests) in the bucket: water leaks
 * out at one unit per leak period, and a request is allowed only if the bucket
 * has room (water &lt; capacity). State transitions are atomic via a Lua script.
 */
@Component
public class LeakyBucketLimiter implements RateLimiter {

    private static final String LUA_SCRIPT = """
            local now = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local periodMs = tonumber(ARGV[3])
            local ttlMs = tonumber(ARGV[4])
            local value = redis.call('GET', KEYS[1])
            local water, lastLeak
            if value then
              local idx = string.find(value, ':')
              water = tonumber(string.sub(value, 1, idx - 1))
              lastLeak = tonumber(string.sub(value, idx + 1))
              local periods = math.floor((now - lastLeak) / periodMs)
              if periods > 0 then
                water = math.max(0, water - periods)
                lastLeak = lastLeak + periods * periodMs
              end
            else
              water = 0
              lastLeak = now
            end
            if water >= capacity then
              return -1
            end
            water = water + 1
            redis.call('SET', KEYS[1], water .. ':' .. lastLeak, 'PX', ttlMs)
            return capacity - water
            """;

    private final StringRedisTemplate redisTemplate;
    private final long capacity;
    private final Duration leakPeriod;
    private final RedisScript<Long> script;
    private final RedisGuard guard;

    @Autowired
    public LeakyBucketLimiter(StringRedisTemplate redisTemplate, RateLimiterProperties properties,
                              RedisGuard guard) {
        this.redisTemplate = redisTemplate;
        this.capacity = properties.getLimits().getOrDefault("LEAKY_BUCKET", properties.getDefaultLimit());
        this.leakPeriod = properties.getWindows().getOrDefault("LEAKY_BUCKET", Duration.ofSeconds(properties.getDefaultWindowSec()));
        this.script = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
        this.guard = guard;
    }

    @Override
    public boolean allow(String clientId) {
        long now = Instant.now().toEpochMilli();
        Long remaining = guard.execute("leakybucket:allow",
                () -> redisTemplate.execute(script, List.of(key(clientId)),
                        String.valueOf(now), String.valueOf(capacity), String.valueOf(leakPeriod.toMillis()),
                        String.valueOf(leakPeriod.multipliedBy(Math.max(1L, capacity * 2)).toMillis())));
        if (remaining == null) {
            return true; // Redis unavailable – fail open
        }
        return remaining >= 0;
    }

    @Override
    public long getLimit() {
        return capacity;
    }

    @Override
    public long getWindowSeconds() {
        return leakPeriod.toSeconds();
    }

    @Override
    public long remaining(String clientId) {
        Long remaining = guard.execute("leakybucket:remaining", () -> {
            String value = redisTemplate.opsForValue().get(key(clientId));
            if (value == null) {
                return capacity;
            }
            String[] parts = value.split(":");
            long water = Long.parseLong(parts[0]);
            long lastLeak = Long.parseLong(parts[1]);
            long now = Instant.now().toEpochMilli();
            long periods = (now - lastLeak) / leakPeriod.toMillis();
            if (periods > 0) {
                water = Math.max(0L, water - periods);
            }
            return Math.max(0L, capacity - water);
        });
        return remaining == null ? capacity : remaining;
    }

    private String key(String clientId) {
        return "leakybucket:" + clientId;
    }
}
