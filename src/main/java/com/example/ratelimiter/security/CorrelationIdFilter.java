package com.example.ratelimiter.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.UUID;

/**
 * Adds a unique {@code X-Request-Id} header to each incoming request and stores it in the
 * MDC (Mapped Diagnostic Context) so that the value is automatically included in log entries.
 * This enables request‑level tracing across the service without pulling in the full Spring Security
 * stack.
 */
@Component
public class CorrelationIdFilter implements Filter {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        // Preserve an incoming ID if the client already supplied one, otherwise generate a new UUID.
        String requestId = httpRequest.getHeader(REQUEST_ID_HEADER);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        // Put into MDC so all log statements within the processing thread have the ID.
        MDC.put(REQUEST_ID_HEADER, requestId);
        // Ensure the response also contains the header for downstream services.
        httpResponse.setHeader(REQUEST_ID_HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Clean up MDC to avoid leaking the ID to other requests handled by the same thread.
            MDC.remove(REQUEST_ID_HEADER);
        }
    }
}
