package com.example.ratelimiter.security;

import com.example.ratelimiter.config.RateLimiterProperties;
import io.micrometer.core.instrument.Counter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.StaticHeadersWriter;

/**
 * Single, ordered security filter chain for the whole application:
 * <ul>
 *   <li>API-key authentication for {@code /api/v1/**} (see {@link ApiKeyAuthFilter})</li>
 *   <li>public health / Prometheus / Swagger endpoints</li>
 *   <li>all other actuator endpoints denied</li>
 *   <li>security headers (HSTS, CSP, nosniff, frame, referrer) applied to every response</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    private final RateLimiterProperties properties;
    private final Counter invalidApiKeyCounter;

    public SecurityConfig(RateLimiterProperties properties, Counter invalidApiKeyCounter) {
        this.properties = properties;
        this.invalidApiKeyCounter = invalidApiKeyCounter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        ApiKeyAuthFilter apiKeyFilter = new ApiKeyAuthFilter(properties, invalidApiKeyCounter);
        http
            .csrf(csrf -> csrf.disable())
            .headers(headers -> headers
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000))
                .contentSecurityPolicy(csp -> csp
                    .policyDirectives("default-src 'self'"))
                .addHeaderWriter(new StaticHeadersWriter("X-Content-Type-Options", "nosniff"))
                .addHeaderWriter(new StaticHeadersWriter("X-Frame-Options", "DENY"))
                .addHeaderWriter(new StaticHeadersWriter("Referrer-Policy", "no-referrer")))
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/actuator/health", "/actuator/prometheus",
                        "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                .requestMatchers("/actuator/**").denyAll()
                .requestMatchers("/api/v1/**").authenticated()
                .anyRequest().permitAll())
            .addFilterBefore(apiKeyFilter, UsernamePasswordAuthenticationFilter.class)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
