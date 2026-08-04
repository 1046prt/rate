package com.example.ratelimiter.algorithm;

/**
 * Common interface for all rate limiting algorithms.
 */
public interface RateLimiter {

    /**
     * Determines whether a request from the given client should be allowed.
     *
     * @param clientId unique identifier for the client (e.g., user ID or IP)
     * @return true if the request is allowed, false otherwise
     */
    boolean allow(String clientId);

    /**
     * @return the configured request limit for this algorithm, or -1 if unknown
     */
    default long getLimit() {
        return -1L;
    }

    /**
     * @return the configured window size in seconds, or -1 if unknown
     */
    default long getWindowSeconds() {
        return -1L;
    }

    /**
     * @return the remaining number of requests/tokens for the client, or -1 if not available
     */
    default long remaining(String clientId) {
        return -1L;
    }
}
