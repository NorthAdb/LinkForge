package com.linkforge.security;

import com.linkforge.config.LinkForgeProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * B1: guards /actuator/prometheus with the dedicated scrape token (D-Bw345-42).
 * An unconfigured token is fail-closed — the endpoint rejects every request — because
 * a metrics leak is worse than a missing scrape. A matching token earns ROLE_METRICS;
 * everything else about the actuator surface is untouched.
 */
@Component
@RequiredArgsConstructor
public class MetricsScrapeTokenFilter extends OncePerRequestFilter {

    private final LinkForgeProperties properties;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals("/actuator/prometheus");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String configured = properties.getMetrics().getScrapeToken();
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        String provided = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        if (configured == null || configured.isBlank() || provided == null
                || !MessageDigest.isEqual(configured.getBytes(StandardCharsets.UTF_8),
                        provided.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"unauthorized\",\"data\":null}");
            return;
        }
        var authentication = new UsernamePasswordAuthenticationToken(
                "metrics-scrape", null, List.of(new SimpleGrantedAuthority("ROLE_METRICS")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
