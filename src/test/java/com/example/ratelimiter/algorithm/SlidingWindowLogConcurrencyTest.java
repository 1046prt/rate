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
 * Concurrency test for the sliding window log: a parallel burst must never over-admit
 * beyond the configured limit, proving the Lua trim+check+add is atomic.
 */
@SpringBootTest
@Import(TestRedisConfig.class)
public class SlidingWindowLogConcurrencyTest {

    private static final int LIMIT = 50;
    private static final int THREADS = 10;
    private static final int REQUESTS_PER_THREAD = 10;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private SlidingWindowLogLimiter limiter;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();
        RateLimiterProperties props = new RateLimiterProperties();
        props.setLimits(Map.of("SLIDING_LOG", (long) LIMIT));
        props.setWindows(Map.of("SLIDING_LOG", Duration.ofSeconds(60)));
        limiter = new SlidingWindowLogLimiter(redisTemplate, props, TestGuard.guard());
    }

    @Test
    void neverOverAdmitsUnderParallelBurst() throws Exception {
        assertEquals(LIMIT, runBurst("burst"), "Exactly " + LIMIT + " requests must be allowed");
    }

    @Test
    void differentClientsAreIndependent() throws Exception {
        assertEquals(LIMIT, runBurst("clientA"));
        assertEquals(LIMIT, runBurst("clientB"));
    }

    private int runBurst(String clientId) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < THREADS; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < REQUESTS_PER_THREAD; i++) {
                        if (limiter.allow(clientId)) {
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
        return allowed.get();
    }
}
