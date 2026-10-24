package com.linkforge.security;

import com.linkforge.apikey.entity.ApiKey;
import com.linkforge.apikey.service.ApiKeyService;
import com.linkforge.common.ratelimit.RedisRateLimiter;
import com.linkforge.config.LinkForgeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * B-wave2 B4: authenticates the X-API-Key header (never Authorization — a non-JWT
 * bearer string would be rejected by JwtAuthFilter, D-Bw2-2). A valid key acts as
 * its creating user with ROLE_API_KEY; JWT and key together are a 400, invalid or
 * revoked keys a 401, quota exhaustion a 429 with Retry-After. All bodies mirror
 * the GlobalExceptionHandler shapes because filter exceptions never reach it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";
    private static final String TOUCH_KEY_PREFIX = "linkforge:apikey:touch:";

    private final ApiKeyService apiKeyService;
    private final RedisRateLimiter rateLimiter;
    private final LinkForgeProperties properties;
    private final MeterRegistry meterRegistry;
    private final StringRedisTemplate stringRedisTemplate;

    /** /api/** and non-health actuator inspect keys (a presented key there earns a
     * clean 403 instead of an anonymous 401); /r/** and health never see this filter. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/")) {
            return false;
        }
        return !uri.startsWith("/actuator/") || uri.equals("/actuator/health");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (authorization != null && authorization.startsWith("Bearer ")) {
                meterRegistry.counter("linkforge.apikey.auth", "result", "ambiguous").increment();
                writeJson(response, 400, "{\"code\":400,\"message\":\"ambiguous credentials: send either a bearer token or X-API-Key\",\"data\":null}");
                return;
            }
            ApiKey key = apiKeyService.authenticate(apiKey);
            if (key == null) {
                meterRegistry.counter("linkforge.apikey.auth", "result", "invalid").increment();
                writeJson(response, 401, "{\"code\":401,\"message\":\"invalid api key\",\"data\":null}");
                return;
            }
            if (key.getRevokedAt() != null) {
                // still a uniform 401 outside; the tag just separates the reasons (Bw2-N3)
                meterRegistry.counter("linkforge.apikey.auth", "result", "revoked").increment();
                writeJson(response, 401, "{\"code\":401,\"message\":\"invalid api key\",\"data\":null}");
                return;
            }
            if (!withinQuota(key, response)) {
                return;
            }
            meterRegistry.counter("linkforge.apikey.auth", "result", "ok").increment();
            touchLastUsed(key);

            AuthUser authUser = new AuthUser(key.getUserId(), key.getTenantId(), "apikey:" + key.getKeyPrefix());
            UserContext.set(authUser);
            var authentication = new UsernamePasswordAuthenticationToken(
                    authUser, null, List.of(new SimpleGrantedAuthority("ROLE_API_KEY")));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /** Minute window first, then the Asia/Shanghai calendar day; fail-open inside RedisRateLimiter. */
    private boolean withinQuota(ApiKey key, HttpServletResponse response) throws IOException {
        LinkForgeProperties.ApiKey cfg = properties.getApiKey();
        String rlPrefix = properties.getRateLimit().getKeyPrefix() + "apikey:";
        int perMinute = key.getQuotaPerMinute() != null ? key.getQuotaPerMinute() : cfg.getDefaultQuotaPerMinute();
        int perDay = key.getQuotaPerDay() != null ? key.getQuotaPerDay() : cfg.getDefaultQuotaPerDay();

        if (!rateLimiter.tryAcquire(rlPrefix + "min:" + key.getId(), perMinute, 60)) {
            meterRegistry.counter("linkforge.apikey.quota.rejected", "window", "minute").increment();
            writeQuotaExceeded(response, 60);
            return false;
        }
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        LocalDate today = LocalDate.now(zone);
        String day = today.format(DateTimeFormatter.BASIC_ISO_DATE);
        if (!rateLimiter.tryAcquire(rlPrefix + "day:" + key.getId() + ":" + day, perDay, 86_400)) {
            meterRegistry.counter("linkforge.apikey.quota.rejected", "window", "day").increment();
            long secondsToMidnight = java.time.Duration.between(
                    LocalDateTime.now(zone),
                    today.plusDays(1).atStartOfDay(zone)).getSeconds();
            writeQuotaExceeded(response, Math.max(1, (int) secondsToMidnight));
            return false;
        }
        return true;
    }

    private void writeQuotaExceeded(HttpServletResponse response, int retryAfterSeconds) throws IOException {
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
        writeJson(response, 429, "{\"code\":429,\"message\":\"too many requests, retry later\",\"data\":null}");
    }

    private void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
    }

    /** Best-effort: at most one UPDATE per key per minute, failures swallowed. */
    private void touchLastUsed(ApiKey key) {
        try {
            Boolean first = stringRedisTemplate.opsForValue()
                    .setIfAbsent(TOUCH_KEY_PREFIX + key.getId(), "1", Duration.ofSeconds(60));
            if (Boolean.TRUE.equals(first)) {
                apiKeyService.touch(key.getId());
            }
        } catch (Exception ex) {
            log.warn("api key last-used touch failed for {}: {}", key.getId(), ex.getMessage());
        }
    }
}
