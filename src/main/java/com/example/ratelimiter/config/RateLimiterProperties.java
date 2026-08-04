package com.example.ratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration properties for rate limiting algorithms and security.
 *
 * Example configuration in <code>application.yml</code>:
 *
 * <pre>
 * ratelimiter:
 *   apiKey: my-secret-key
 *   limits:
 *     FIXED: 100
 *     SLIDING_LOG: 100
 *     SLIDING_COUNTER: 100
 *     TOKEN_BUCKET: 100
 *     LEAKY_BUCKET: 100
 *   windows:
 *     FIXED: PT1M
 *     SLIDING_LOG: PT1M
 *     SLIDING_COUNTER: PT1M
 *     TOKEN_BUCKET: PT1M
 *     LEAKY_BUCKET: PT1S
 * </pre>
 */
@ConfigurationProperties(prefix = "ratelimiter")
@org.springframework.validation.annotation.Validated
public class RateLimiterProperties {

    /** API key required for requests (optional – leave blank for open access) */
    private String apiKey;

    /** Maximum number of requests allowed per client for each algorithm. */
    private Map<String, Long> limits = new HashMap<>();

    /** Duration of the sliding/fixed window for each algorithm. */
    private Map<String, Duration> windows = new HashMap<>();

    /** Default request limit if not specified per algorithm */
    private long defaultLimit = 100L;

    /** Default window size in seconds if not specified per algorithm */
    private int defaultWindowSec = 60;

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public Map<String, Long> getLimits() {
        return limits;
    }

    public void setLimits(Map<String, Long> limits) {
        this.limits = limits;
    }

    public Map<String, Duration> getWindows() {
        return windows;
    }

    public void setWindows(Map<String, Duration> windows) {
        this.windows = windows;
    }

    public long getDefaultLimit() {
        return defaultLimit;
    }

    public void setDefaultLimit(long defaultLimit) {
        this.defaultLimit = defaultLimit;
    }

    public int getDefaultWindowSec() {
        return defaultWindowSec;
    }

    public void setDefaultWindowSec(int defaultWindowSec) {
        this.defaultWindowSec = defaultWindowSec;
    }
}
