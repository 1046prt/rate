package com.example.ratelimiter.security;

import com.example.ratelimiter.config.RateLimiterProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ApiKeyAuthFilter}: key validation, open-access mode,
 * non-API paths pass-through, and the invalid-key 401 contract.
 */
class ApiKeyAuthFilterTest {

    private static final String KEY = "secret-key-123";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private ApiKeyAuthFilter filterWithKey() {
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setApiKey(KEY);
        return new ApiKeyAuthFilter(properties, new SimpleMeterRegistry().counter("test.invalid"));
    }

    private ApiKeyAuthFilter filterWithOpenAccess() {
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setApiKey("");
        return new ApiKeyAuthFilter(properties, new SimpleMeterRegistry().counter("test.invalid"));
    }

    @Test
    void acceptsValidKeyAndAuthenticates() throws Exception {
        ApiKeyAuthFilter filter = filterWithKey();
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/api/v1/check");
        when(request.getHeader("X-Api-Key")).thenReturn(KEY);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertTrue(auth.isAuthenticated());
        assertEquals("ROLE_API", auth.getAuthorities().iterator().next().getAuthority());
    }

    @Test
    void rejectsMissingKeyWith401AndDoesNotContinueChain() throws Exception {
        ApiKeyAuthFilter filter = filterWithKey();
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        StringWriter body = new StringWriter();
        when(request.getRequestURI()).thenReturn("/api/v1/check");
        when(request.getHeader("X-Api-Key")).thenReturn(null);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(401);
        assertTrue(body.toString().contains("Invalid API key"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void rejectsWrongKeyWith401() throws Exception {
        ApiKeyAuthFilter filter = filterWithKey();
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/api/v1/check");
        when(request.getHeader("X-Api-Key")).thenReturn("wrong-key");
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(401);
    }

    @Test
    void openAccessWhenNoKeyConfigured() throws Exception {
        ApiKeyAuthFilter filter = filterWithOpenAccess();
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/api/v1/check");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertTrue(SecurityContextHolder.getContext().getAuthentication().isAuthenticated());
    }

    @Test
    void nonApiPathPassesThroughWithoutAuthentication() throws Exception {
        ApiKeyAuthFilter filter = filterWithKey();
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/actuator/health");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void swaggerPathWithoutKeyReturns401() throws Exception {
        ApiKeyAuthFilter filter = filterWithKey();
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        StringWriter body = new StringWriter();
        when(request.getRequestURI()).thenReturn("/swagger-ui/index.html");
        when(request.getHeader("X-Api-Key")).thenReturn(null);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(request, response);
        verify(response).setStatus(401);
    }

    @Test
    void swaggerPathWithValidKeyAuthenticates() throws Exception {
        ApiKeyAuthFilter filter = filterWithKey();
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getRequestURI()).thenReturn("/v3/api-docs");
        when(request.getHeader("X-Api-Key")).thenReturn(KEY);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertTrue(SecurityContextHolder.getContext().getAuthentication().isAuthenticated());
    }
}
