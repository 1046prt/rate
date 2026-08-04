package com.example.ratelimiter.exception;

/**
 * Thrown when a client exceeds the configured rate limit.
 * Mapped to HTTP 429 by {@link GlobalExceptionHandler}.
 */
public class RateLimitExceededException extends RuntimeException {

    private final long limit;
    private final long retryAfterSeconds;

    public RateLimitExceededException(String message) {
        this(message, -1L, -1L);
    }

    public RateLimitExceededException(String message, long limit, long retryAfterSeconds) {
        super(message);
        this.limit = limit;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getLimit() {
        return limit;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
