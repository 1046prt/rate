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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end REST tests covering the full request flow: authentication, rate-limit
 * decision, rate-limit response headers, 429 with Retry-After, and validation errors.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestRedisConfig.class)
public class RateLimiterControllerTest {

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

    private ResponseEntity<CheckResponseDto> post(String clientId, String algorithm) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        CheckRequestDto body = new CheckRequestDto();
        body.setClientId(clientId);
        body.setAlgorithm(algorithm);
        return restTemplate.postForEntity("/api/v1/check", new HttpEntity<>(body, headers), CheckResponseDto.class);
    }
}
