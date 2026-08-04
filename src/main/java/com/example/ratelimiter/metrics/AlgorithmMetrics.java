package com.example.ratelimiter.metrics;

import com.example.ratelimiter.service.RateLimiterFactory.Algorithm;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registers a Prometheus counter for each rate‑limiting algorithm and exposes
 * a lookup by {@link Algorithm}. Counters are incremented on every request
 * handled by that algorithm (both the REST controller and the AOP aspect path).
 */
@Component
public class AlgorithmMetrics {

    private final Map<Algorithm, Counter> counters = new EnumMap<>(Algorithm.class);

    public AlgorithmMetrics(MeterRegistry meterRegistry) {
        for (Algorithm algorithm : Algorithm.values()) {
            counters.put(algorithm, Counter.builder("ratelimiter.algorithm.calls")
                    .description("Number of calls per rate limiting algorithm")
                    .tag("algorithm", algorithm.name())
                    .register(meterRegistry));
        }
    }

    public void increment(Algorithm algorithm) {
        Counter counter = counters.get(algorithm);
        if (counter != null) {
            counter.increment();
        }
    }
}
