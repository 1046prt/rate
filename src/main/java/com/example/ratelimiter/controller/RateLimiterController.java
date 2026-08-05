package com.example.ratelimiter.controller;

import com.example.ratelimiter.dto.CheckRequestDto;
import com.example.ratelimiter.dto.CheckResponseDto;
import com.example.ratelimiter.service.RateLimiterFactory;
import com.example.ratelimiter.algorithm.RateLimiter;
import com.example.ratelimiter.exception.RateLimitExceededException;
import com.example.ratelimiter.metrics.AlgorithmMetrics;
import io.micrometer.core.instrument.Counter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Rate Limiting", description = "Rate-limit check endpoint")
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

    @Operation(
            summary = "Check whether a request is within its rate limit",
            description = """
                    Decides, per `clientId`, whether a request is allowed or must be throttled using the \
                    chosen algorithm. The decision is atomic in Redis and shared across all replicas.

                    Rate-limit headers are returned on both 200 and 429 responses:
                    `X-RateLimit-Limit` (configured limit), `X-RateLimit-Remaining` (200 only) and \
                    `Retry-After` (429 only). Every response carries an `X-Request-Id` for log correlation.

                    Sending more requests than the limit within the window yields 429; clients should \
                    back off for `Retry-After` seconds.
                    """)
    @SecurityRequirement(name = "apiKey")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request allowed",
                    headers = {
                            @Header(name = "X-RateLimit-Limit", description = "Configured limit for the algorithm", schema = @Schema(type = "integer")),
                            @Header(name = "X-RateLimit-Remaining", description = "Remaining requests in the current window", schema = @Schema(type = "integer")),
                            @Header(name = "X-Request-Id", description = "Correlation id", schema = @Schema(type = "string"))
                    },
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CheckResponseDto.class),
                            examples = @ExampleObject(value = "{\"allowed\":true,\"message\":\"Request allowed\"}"))),
            @ApiResponse(responseCode = "429", description = "Rate limit exceeded - throttle (back off for Retry-After seconds)",
                    headers = {
                            @Header(name = "X-RateLimit-Limit", description = "Configured limit for the algorithm", schema = @Schema(type = "integer")),
                            @Header(name = "Retry-After", description = "Seconds until the window resets", schema = @Schema(type = "integer")),
                            @Header(name = "X-Request-Id", description = "Correlation id", schema = @Schema(type = "string"))
                    },
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CheckResponseDto.class),
                            examples = @ExampleObject(value = "{\"allowed\":false,\"message\":\"Rate limit exceeded for client user123\"}"))),
            @ApiResponse(responseCode = "400", description = "Missing/invalid clientId or algorithm, or malformed JSON body",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = "{\"message\":\"clientId: must not be blank\"}"))),
            @ApiResponse(responseCode = "401", description = "Missing or invalid X-Api-Key (only when RATELIMITER_API_KEY is set)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = "{\"error\":\"Invalid API key\"}"))),
            @ApiResponse(responseCode = "500", description = "Unexpected server error (fail-open policy prevents Redis outages from reaching this code path)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            examples = @ExampleObject(value = "{\"message\":\"Internal server error\"}")))
    })
    @PostMapping(value = "/check", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
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
