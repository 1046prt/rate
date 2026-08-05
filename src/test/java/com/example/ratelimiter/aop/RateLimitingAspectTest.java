package com.example.ratelimiter.aop;

import com.example.ratelimiter.annotation.RateLimited;
import com.example.ratelimiter.exception.RateLimitExceededException;
import com.example.ratelimiter.metrics.AlgorithmMetrics;
import com.example.ratelimiter.service.RateLimiterFactory;
import com.example.ratelimiter.algorithm.RateLimiter;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RateLimitingAspect}: allowed calls proceed, throttled calls
 * throw {@link RateLimitExceededException}, and missing clientId is rejected up front.
 */
class RateLimitingAspectTest {

    private final RateLimiter limiter = mock(RateLimiter.class);
    private final RateLimiterFactory factory = mock(RateLimiterFactory.class);
    private final AlgorithmMetrics metrics = mock(AlgorithmMetrics.class);
    private final ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);

    private RateLimitingAspect aspect() {
        return new RateLimitingAspect(factory, metrics);
    }

    private RateLimited rateLimited() {
        RateLimited annotation = mock(RateLimited.class);
        when(annotation.algorithm()).thenReturn(RateLimiterFactory.Algorithm.FIXED);
        return annotation;
    }

    @Test
    void proceedsWhenWithinLimit() throws Throwable {
        when(pjp.getArgs()).thenReturn(new Object[]{"clientA"});
        when(factory.getLimiter("FIXED")).thenReturn(limiter);
        when(limiter.allow("clientA")).thenReturn(true);
        when(pjp.proceed()).thenReturn("business-result");

        Object result = aspect().enforceRateLimit(pjp, rateLimited());

        assertEquals("business-result", result);
        verify(pjp).proceed();
    }

    @Test
    void throwsRateLimitExceededWhenThrottled() throws Throwable {
        when(pjp.getArgs()).thenReturn(new Object[]{"clientB"});
        when(factory.getLimiter("FIXED")).thenReturn(limiter);
        when(limiter.allow("clientB")).thenReturn(false);

        assertThrows(RateLimitExceededException.class,
                () -> aspect().enforceRateLimit(pjp, rateLimited()));
        verify(pjp, never()).proceed();
    }

    @Test
    void rejectsMissingClientIdArgument() throws Throwable {
        when(pjp.getArgs()).thenReturn(new Object[]{42L});

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> aspect().enforceRateLimit(pjp, rateLimited()));

        assertTrue(ex.getMessage().contains("String clientId"));
        verify(pjp, never()).proceed();
    }
}
