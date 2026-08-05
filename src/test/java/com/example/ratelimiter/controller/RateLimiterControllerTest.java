package com.example.ratelimiter.controller;

import com.example.ratelimiter.config.TestRedisConfig;
import com.example.ratelimiter.dto.CheckRequestDto;
import com.example.ratelimiter.dto.CheckResponseDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end REST tests covering the full request flow: authentication, rate-limit
 * decision, rate-limit response headers, 429 with Retry-After, and validation errors.
 * Runs against a real Redis container (Testcontainers) and the real security filter chain.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ratelimiter.api-key=test-api-key")
@Import(TestRedisConfig.class)
public class RateLimiterControllerTest {

    private static final String API_KEY = "test-api-key";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void setUp() {
        redisTemplate.getConnectionFactory().getConnection().flushAll();
    }

    @Test
    void returns200WithRateLimitHeadersWithinLimit() {
        ResponseEntity<CheckResponseDto> response = null;
        for (int i = 0; i < 100; i++) {
            response = post("user1", "FIXED");
            assertEquals(HttpStatus.OK, response.getStatusCode(), "Request " + i + " should be allowed");
        }
        assertEquals("100", response.getHeaders().getFirst("X-RateLimit-Limit"));
        assertEquals("0", response.getHeaders().getFirst("X-RateLimit-Remaining"));
    }

    @Test
    void returns429WithRetryAfterBeyondLimit() {
        for (int i = 0; i < 100; i++) {
            assertEquals(HttpStatus.OK, post("user2", "FIXED").getStatusCode());
        }
        ResponseEntity<CheckResponseDto> response = post("user2", "FIXED");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("100", response.getHeaders().getFirst("X-RateLimit-Limit"));
        assertEquals("60", response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
        assertEquals(false, response.getBody().isAllowed());
    }

    @Test
    void rejectsBlankClientIdWith400() {
        ResponseEntity<CheckResponseDto> response = post("", "FIXED");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void rejectsUnknownAlgorithmWith400() {
        ResponseEntity<CheckResponseDto> response = post("user3", "NOPE");
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void clientsAreIsolated() {
        for (int i = 0; i < 100; i++) {
            assertEquals(HttpStatus.OK, post("userA", "FIXED").getStatusCode());
        }
        assertTrue(post("userB", "FIXED").getStatusCode().is2xxSuccessful());
    }

    @Test
    void rejectsMalformedJsonWith400() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", API_KEY);
        HttpEntity<String> malformed = new HttpEntity<>("{invalid json", headers);
        ResponseEntity<CheckResponseDto> response =
                restTemplate.postForEntity("/api/v1/check", malformed, CheckResponseDto.class);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void rejectsUnknownPathWith404() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", API_KEY);
        HttpEntity<String> request = new HttpEntity<>("{}", headers);
        ResponseEntity<Map> response =
                restTemplate.postForEntity("/api/v1/nonexistent", request, Map.class);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("Resource not found", response.getBody().get("message"));
    }

    @Test
    void rejectsMissingApiKeyWith401() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        CheckRequestDto body = new CheckRequestDto();
        body.setClientId("user401");
        body.setAlgorithm("FIXED");
        HttpEntity<CheckRequestDto> noKey = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/check", noKey, String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        assertTrue(response.getBody().contains("Invalid API key"));
    }

    @Test
    void swaggerDocsRequireApiKey() {
        ResponseEntity<String> noKey = restTemplate.getForEntity("/v3/api-docs", String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, noKey.getStatusCode());

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Api-Key", API_KEY);
        ResponseEntity<String> withKey =
                restTemplate.exchange("/v3/api-docs", HttpMethod.GET,
                        new HttpEntity<>(headers), String.class);
        assertEquals(HttpStatus.OK, withKey.getStatusCode());
        assertTrue(withKey.getBody().contains("/api/v1/check"));
    }

    private ResponseEntity<CheckResponseDto> post(String clientId, String algorithm) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Api-Key", API_KEY);
        CheckRequestDto body = new CheckRequestDto();
        body.setClientId(clientId);
        body.setAlgorithm(algorithm);
        return restTemplate.postForEntity("/api/v1/check", new HttpEntity<>(body, headers), CheckResponseDto.class);
    }
}
