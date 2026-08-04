package com.example.ratelimiter.annotation;

import com.example.ratelimiter.service.RateLimiterFactory;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to apply rate limiting to any Spring bean method.
 *
 * <p>Example usage:
 * <pre>
 * @RateLimited(algorithm = RateLimiterFactory.Algorithm.FIXED, limit = 50, windowSec = 30)
 * public void myMethod(String clientId) { ... }
 * </pre>
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimited {
    /** The algorithm to use for limiting. */
    RateLimiterFactory.Algorithm algorithm();

    /** Optional override of the request limit (default = -1 means use configured limit). */
    long limit() default -1L;

    /** Optional override of the window size in seconds (default = -1 means use configured window). */
    int windowSec() default -1;
}
