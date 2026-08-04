package com.example.ratelimiter.service;

import com.example.ratelimiter.algorithm.RateLimiter;
import com.example.ratelimiter.algorithm.FixedWindowLimiter;
import com.example.ratelimiter.algorithm.SlidingWindowLogLimiter;
import com.example.ratelimiter.algorithm.SlidingWindowCounterLimiter;
import com.example.ratelimiter.algorithm.TokenBucketLimiter;
import com.example.ratelimiter.algorithm.LeakyBucketLimiter;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Factory that returns the appropriate {@link RateLimiter} implementation based on the algorithm name.
 *
 * The supported algorithm identifiers (case‑insensitive) are:
 * <ul>
 *   <li>FIXED</li>
 *   <li>SLIDING_LOG</li>
 *   <li>SLIDING_COUNTER</li>
 *   <li>TOKEN_BUCKET</li>
 *   <li>LEAKY_BUCKET</li>
 * </ul>
 */
@Component
public class RateLimiterFactory {

    private final Map<Algorithm, RateLimiter> limiterMap = new EnumMap<>(Algorithm.class);

    public enum Algorithm {
        FIXED,
        SLIDING_LOG,
        SLIDING_COUNTER,
        TOKEN_BUCKET,
        LEAKY_BUCKET
    }

    public RateLimiterFactory(FixedWindowLimiter fixedWindowLimiter,
                              SlidingWindowLogLimiter slidingWindowLogLimiter,
                              SlidingWindowCounterLimiter slidingWindowCounterLimiter,
                              TokenBucketLimiter tokenBucketLimiter,
                              LeakyBucketLimiter leakyBucketLimiter) {
        limiterMap.put(Algorithm.FIXED, fixedWindowLimiter);
        limiterMap.put(Algorithm.SLIDING_LOG, slidingWindowLogLimiter);
        limiterMap.put(Algorithm.SLIDING_COUNTER, slidingWindowCounterLimiter);
        limiterMap.put(Algorithm.TOKEN_BUCKET, tokenBucketLimiter);
        limiterMap.put(Algorithm.LEAKY_BUCKET, leakyBucketLimiter);
    }

    /**
     * Returns a {@link RateLimiter} for the given algorithm name.
     *
     * @param algorithmName name of the algorithm (e.g., "FIXED", "TOKEN_BUCKET").
     * @return the matching RateLimiter implementation.
     * @throws IllegalArgumentException if the algorithm is unknown.
     */
    public RateLimiter getLimiter(String algorithmName) {
        if (algorithmName == null) {
            throw new IllegalArgumentException("Algorithm name must not be null");
        }
        try {
            Algorithm algo = Algorithm.valueOf(algorithmName.trim().toUpperCase());
            RateLimiter limiter = limiterMap.get(algo);
            if (limiter == null) {
                throw new IllegalArgumentException("No RateLimiter found for algorithm: " + algorithmName);
            }
            return limiter;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported rate limiting algorithm: " + algorithmName, e);
        }
    }
}
