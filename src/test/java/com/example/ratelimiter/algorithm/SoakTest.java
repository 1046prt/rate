package com.example.ratelimiter.algorithm;

import com.example.ratelimiter.config.RateLimiterProperties;
import com.example.ratelimiter.config.TestGuard;
import com.example.ratelimiter.config.TestRedisConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
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
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sustained-load soak test. Excluded from the default test run (tag {@code soak}) because it
 * intentionally hammers Redis for a fixed duration; run explicitly with:
 *
 * <pre>mvn test -Psoak</pre>
 *
 * Measures throughput and latency under sustained parallel load and asserts no errors.
 */
@Tag("soak")
@SpringBootTest
@Import(TestRedisConfig.class)
public class SoakTest {

    private static final int THREADS = 20;
    private static final Duration DURATION = Duration.ofSeconds(10);
    private static final long LIMIT = 100_000L; // far above traffic so nothing is throttled

    @Autowired
    private StringRedisTemplate redisTemplate;

    private FixedWindowLimiter limiter;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();
        RateLimiterProperties props = new RateLimiterProperties();
        props.setLimits(Map.of("FIXED", LIMIT));
        props.setWindows(Map.of("FIXED", Duration.ofSeconds(60)));
        limiter = new FixedWindowLimiter(redisTemplate, props, TestGuard.guard());
    }

    @Test
    void sustainedLoadStaysErrorFree() throws Exception {
        AtomicLong allowed = new AtomicLong();
        AtomicLong rejected = new AtomicLong();
        long failures = runFor(DURATION, allowed, rejected);

        long total = allowed.get() + rejected.get();
        assertTrue(total > 1_000, "expected meaningful throughput, got " + total + " calls");
        assertEquals(0L, failures, "no call may throw during the soak");
        double rps = total / (double) DURATION.toMillis() * 1000;
        System.out.printf("SOAK: %d calls in %s (%.0f req/s), %d allowed, %d rejected, %d failures%n",
                total, DURATION, rps, allowed.get(), rejected.get(), failures);
    }

    private long runFor(Duration duration, AtomicLong allowed, AtomicLong rejected) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch stop = new CountDownLatch(1);
        List<Future<Long>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < THREADS; t++) {
                final int thread = t;
                futures.add(pool.submit(() -> {
                    long failures = 0;
                    try {
                        start.await();
                        while (!stop.await(1, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                            try {
                                if (limiter.allow("soak-" + thread)) {
                                    allowed.incrementAndGet();
                                } else {
                                    rejected.incrementAndGet();
                                }
                            } catch (Exception e) {
                                failures++;
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return failures;
                }));
            }
            start.countDown();
            Thread.sleep(duration.toMillis());
            stop.countDown();
            long failures = 0;
            for (Future<Long> future : futures) {
                failures += future.get();
            }
            return failures;
        } finally {
            pool.shutdownNow();
        }
    }
}
