package com.example.ratelimiter.controller;

import com.example.ratelimiter.dto.CheckRequestDto;
import com.example.ratelimiter.dto.CheckResponseDto;
import com.example.ratelimiter.service.RateLimiterFactory;
import com.example.ratelimiter.algorithm.RateLimiter;
import com.example.ratelimiter.exception.RateLimitExceededException;
import com.example.ratelimiter.metrics.AlgorithmMetrics;
import io.micrometer.core.instrument.Counter;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class RateLimiterController {

    private final RateLimiterFactory factory;
    private final AlgorithmMetrics metrics;
    private final Counter allowedCounter;
    private final Counter rejectedCounter;
    private final Counter totalCounter;

    public RateLimiterController(RateLimiterFactory factory,
                                 AlgorithmMetrics metrics,
                                 Counter allowedCounter,
                                 Counter rejectedCounter,
                                 Counter totalCounter) {
        this.factory = factory;
        this.metrics = metrics;
        this.allowedCounter = allowedCounter;
        this.rejectedCounter = rejectedCounter;
        this.totalCounter = totalCounter;
    }

    @PostMapping("/check")
    public ResponseEntity<CheckResponseDto> check(@Valid @RequestBody CheckRequestDto request) {
        totalCounter.increment();
        RateLimiter limiter = factory.getLimiter(request.getAlgorithm());
        metrics.increment(RateLimiterFactory.Algorithm.valueOf(request.getAlgorithm().trim().toUpperCase()));

        boolean allowed = limiter.allow(request.getClientId());
        if (allowed) {
            allowedCounter.increment();
            return ResponseEntity.ok()
                    .header("X-RateLimit-Limit", String.valueOf(limiter.getLimit()))
                    .header("X-RateLimit-Remaining", String.valueOf(limiter.remaining(request.getClientId())))
                    .body(new CheckResponseDto(true, "Request allowed"));
        } else {
            rejectedCounter.increment();
            throw new RateLimitExceededException(
                    "Rate limit exceeded for client " + request.getClientId(),
                    limiter.getLimit(), limiter.getWindowSeconds());
        }
    }
}
