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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Concurrency test for the token bucket: a parallel burst must never consume
 * more tokens than the capacity allows, thanks to the atomic Lua script.
 */
@SpringBootTest
@Import(TestRedisConfig.class)
public class TokenBucketConcurrencyTest {

    private static final int CAPACITY = 50;
    private static final int THREADS = 10;
    private static final int REQUESTS_PER_THREAD = 10;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private TokenBucketLimiter limiter;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();
        RateLimiterProperties props = new RateLimiterProperties();
        props.setLimits(Map.of("TOKEN_BUCKET", (long) CAPACITY));
        props.setWindows(Map.of("TOKEN_BUCKET", Duration.ofSeconds(60)));
        limiter = new TokenBucketLimiter(redisTemplate, props, TestGuard.guard());
    }

    @Test
    void neverOverAdmitsUnderParallelBurst() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < THREADS; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                        if (limiter.allow("burst")) {
                            allowed.incrementAndGet();
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }
        assertEquals(CAPACITY, allowed.get(), "Exactly " + CAPACITY + " requests must be allowed");
    }
}
