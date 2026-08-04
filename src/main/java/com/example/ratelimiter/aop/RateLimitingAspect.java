package com.example.ratelimiter.aop;

import com.example.ratelimiter.annotation.RateLimited;
import com.example.ratelimiter.exception.RateLimitExceededException;
import com.example.ratelimiter.service.RateLimiterFactory;
import com.example.ratelimiter.algorithm.RateLimiter;
import com.example.ratelimiter.metrics.AlgorithmMetrics;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

/**
 * AOP aspect that enforces rate limiting on methods annotated with {@link RateLimited}.
 *
 * It expects the method to have a {@code String clientId} argument (or a first argument that can be
 * converted to {@code String}). If the request exceeds the configured limit, a
 * {@link RateLimitExceededException} is thrown, resulting in an HTTP 429 response.
 */
@Aspect
@Component
public class RateLimitingAspect {

    private final RateLimiterFactory factory;
    private final AlgorithmMetrics metrics;

    public RateLimitingAspect(RateLimiterFactory factory, AlgorithmMetrics metrics) {
        this.factory = factory;
        this.metrics = metrics;
    }

    @Around("@annotation(rateLimited)")
    @CircuitBreaker(name = "rateLimiterCB")
    public Object enforceRateLimit(ProceedingJoinPoint pjp, RateLimited rateLimited) throws Throwable {
        // Resolve clientId – look for a String argument named "clientId" or the first String argument
        Object[] args = pjp.getArgs();
        String clientId = null;
        for (Object arg : args) {
            if (arg instanceof String) {
                clientId = (String) arg;
                break;
            }
        }
        if (clientId == null) {
            throw new IllegalArgumentException("@RateLimited method must have a String clientId argument");
        }

        // Resolve algorithm name
        String algorithmName = rateLimited.algorithm().name();
        RateLimiter limiter = factory.getLimiter(algorithmName);
        boolean allowed = limiter.allow(clientId);
        if (!allowed) {
            throw new RateLimitExceededException("Rate limit exceeded for client " + clientId);
        }
        // Increment metric for the selected algorithm
        metrics.increment(com.example.ratelimiter.service.RateLimiterFactory.Algorithm.valueOf(algorithmName));
        // Proceed with original method execution
        return pjp.proceed();
    }
}
