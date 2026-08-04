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
 * Sliding Window Counter limiter implementation using Redis.
 * Approximates a sliding window by weighting the previous bucket's counter
 * proportionally to how much of the window has elapsed:
 * <pre>estimated = prevCount * (1 - elapsed/bucketSize) + currentCount</pre>
 * Increment and estimation are executed atomically via a Lua script.
 */
@Component
public class SlidingWindowCounterLimiter implements RateLimiter {

    private static final String LUA_SCRIPT = """
            local now = tonumber(ARGV[1])
            local bucketMs = tonumber(ARGV[2])
            local ttl = tonumber(ARGV[3])
            local limit = tonumber(ARGV[4])
            local bucket = math.floor(now / bucketMs)
            local curKey = KEYS[1] .. ':' .. bucket
            local prevKey = KEYS[1] .. ':' .. (bucket - 1)
            local curCount = redis.call('INCR', curKey)
            if curCount == 1 then
              redis.call('EXPIRE', curKey, ttl)
            end
            local prevCount = tonumber(redis.call('GET', prevKey) or '0')
            local elapsed = now - bucket * bucketMs
            local weight = 1 - (elapsed / bucketMs)
            local estimated = prevCount * weight + curCount
            if estimated <= limit then
              return {1, estimated}
            end
            return {0, estimated}
            """;

    private final StringRedisTemplate redisTemplate;
    private final long limit;
    private final Duration window;
    private final RedisScript<List> script;
    private final RedisGuard guard;

    @Autowired
    public SlidingWindowCounterLimiter(StringRedisTemplate redisTemplate, RateLimiterProperties properties,
                                       RedisGuard guard) {
        this.redisTemplate = redisTemplate;
        this.limit = properties.getLimits().getOrDefault("SLIDING_COUNTER", properties.getDefaultLimit());
        this.window = properties.getWindows().getOrDefault("SLIDING_COUNTER", Duration.ofSeconds(properties.getDefaultWindowSec()));
        this.script = new DefaultRedisScript<>(LUA_SCRIPT, List.class);
        this.guard = guard;
    }

    @Override
    public boolean allow(String clientId) {
        long now = Instant.now().toEpochMilli();
        List<Long> result = guard.execute("slidingcnt:allow",
                () -> redisTemplate.execute(script, List.of(key(clientId)),
                        String.valueOf(now), String.valueOf(window.toMillis()),
                        String.valueOf(window.multipliedBy(2).toSeconds()), String.valueOf(limit)));
        if (result == null) {
            return true; // Redis unavailable – fail open
        }
        return !result.isEmpty() && result.get(0) == 1L;
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
        Long remaining = guard.execute("slidingcnt:remaining", () -> {
            long now = Instant.now().toEpochMilli();
            long bucket = now / window.toMillis();
            String baseKey = key(clientId);
            String cur = redisTemplate.opsForValue().get(baseKey + ":" + bucket);
            String prev = redisTemplate.opsForValue().get(baseKey + ":" + (bucket - 1));
            long curCount = cur == null ? 0L : Long.parseLong(cur);
            long prevCount = prev == null ? 0L : Long.parseLong(prev);
            long elapsed = now - bucket * window.toMillis();
            double weight = 1 - (elapsed / (double) window.toMillis());
            double estimated = prevCount * weight + curCount;
            return Math.max(0L, limit - (long) Math.floor(estimated));
        });
        return remaining == null ? limit : remaining;
    }

    private String key(String clientId) {
        return "slidingcnt:" + clientId;
    }
}
