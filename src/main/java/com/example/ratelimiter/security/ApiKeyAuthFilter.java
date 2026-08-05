package com.example.ratelimiter.security;

import com.example.ratelimiter.config.RateLimiterProperties;
import io.micrometer.core.instrument.Counter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * API-key authentication filter for the rate limiter API.
 *
 * <p>Enforced on {@code /api/v1/**} plus the Swagger/OpenAPI endpoints
 * ({@code /swagger-ui/**} and {@code /v3/api-docs/**}); actuator health and Prometheus
 * endpoints stay public. The key is read from the {@code X-Api-Key} header
 * (header names are case-insensitive).
 *
 * <p>On a valid key (or when no key is configured &mdash; open access), an authentication token is
 * placed in the {@link SecurityContextHolder} so the {@code authenticated()} authorization rule
 * in {@link SecurityConfig} passes. On an invalid key a 401 is returned directly.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-Api-Key";
    private static final String API_PATH_PREFIX = "/api/v1";
    private static final String[] PROTECTED_PREFIXES = {API_PATH_PREFIX, "/swagger-ui", "/v3/api-docs"};

    private final RateLimiterProperties properties;
    private final Counter invalidApiKeyCounter;

    public ApiKeyAuthFilter(RateLimiterProperties properties, Counter invalidApiKeyCounter) {
        this.properties = properties;
        this.invalidApiKeyCounter = invalidApiKeyCounter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!isProtectedPath(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String requiredKey = properties.getApiKey();
        if (requiredKey == null || requiredKey.isBlank()) {
            // No API key configured – open access, authenticate every caller.
            setAuthenticated();
            filterChain.doFilter(request, response);
            return;
        }

        String providedKey = request.getHeader(API_KEY_HEADER);
        if (requiredKey.equals(providedKey)) {
            setAuthenticated();
            filterChain.doFilter(request, response);
        } else {
            invalidApiKeyCounter.increment();
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Invalid API key\"}");
        }
    }

    private boolean isProtectedPath(String path) {
        if (path == null) {
            return false;
        }
        for (String prefix : PROTECTED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void setAuthenticated() {
        Authentication authentication = new UsernamePasswordAuthenticationToken(
                "api-client", null, List.of(new SimpleGrantedAuthority("ROLE_API")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
