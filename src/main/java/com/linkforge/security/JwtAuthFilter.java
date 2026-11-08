package com.linkforge.security;

import com.linkforge.user.entity.UserAccount;
import com.linkforge.user.mapper.UserAccountMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserAccountMapper userAccountMapper;

    /** S/N wave (audit S4-5): the recheck store is unavailable — distinct
     * from every credential verdict, on both filters byte-identical. */
    static final String STORE_UNAVAILABLE_BODY =
            "{\"code\":503,\"message\":\"authentication store unavailable\",\"data\":null}";

    /** Paths a must_change_password user may still reach: clearing the flag and
     * ending/rotating the session must never be blocked by the flag itself. */
    private static final Set<String> FORCED_CHANGE_EXEMPT = Set.of(
            "/api/auth/change-password", "/api/auth/logout", "/api/auth/logout-all",
            "/api/auth/refresh", "/actuator/health");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        // the scrape token is not a JWT — parsing it would 401 before the metrics
        // filter can grant ROLE_METRICS (D-Bw345-42)
        if (uri.equals("/actuator/prometheus")) {
            return true;
        }
        // Self-credentialed entries (batch 2a): refresh and logout authenticate by
        // the refresh token in the body, so a dead access token attached to the
        // request must not gate them. Exact matches only — /api/auth/logout-all
        // and /api/auth/change-password DO need the JWT check. accept-invitation
        // (batch 2b) joins this list by the same reasoning.
        return uri.equals("/api/auth/refresh") || uri.equals("/api/auth/logout")
                || uri.equals("/api/auth/accept-invitation");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring(7);
                Claims claims;
                try {
                    claims = jwtService.parse(token);
                } catch (JwtException ex) {
                    // a bad signature / expired token stays a hard 401, as before
                    writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                            "{\"code\":401,\"message\":\"invalid token\",\"data\":null}");
                    return;
                }
                if (!authenticate(request, response, claims)) {
                    return;
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * DB recheck (closes the audit finding "issued tokens are never rechecked"):
     * the user row is re-read per request and identity fields come from the row,
     * never from claims, so disabling, role changes and token_version bumps take
     * effect on the very next request.
     *
     * <p>Failure semantics (batch 2 plan, self-audit A1): a failed recheck does
     * NOT write a 401 here — it simply leaves the request unauthenticated and
     * lets the chain continue. Protected paths then get their 401 from the
     * security entry point, while permitAll paths (e.g. a refresh call that
     * still carries a stale access token) are not collateral damage. Only the
     * forced-password-change block answers directly (403), because the request
     * IS authenticated — it is the one case where continuing the chain would
     * either leak the protected resource or misreport a 401.
     *
     * @return true to continue the chain (authenticated or deliberately not),
     *         false when a response was already written
     */
    private boolean authenticate(HttpServletRequest request, HttpServletResponse response, Claims claims)
            throws IOException {
        Long userId;
        try {
            userId = Long.valueOf(claims.getSubject());
        } catch (RuntimeException ex) {
            return true;
        }
        UserAccount user;
        try {
            user = userAccountMapper.selectById(userId);
        } catch (DataAccessException | PersistenceException ex) {
            // S/N wave (audit S4-5): the recheck STORE failed — that is not
            // a failed recheck. The A1 semantics above cover "row read, user
            // unfit" (leave unauthenticated, chain continues); a store
            // failure instead answers a stable 503 so "dependency down" is
            // never misreported as "bad credential" and never escapes the
            // filter as a container 500 — on protected and permitAll paths
            // alike. No identity is granted. Requests without a token never
            // reach this call and stay unaffected. The log carries the user
            // id only, never the token.
            log.error("auth store unavailable while rechecking user {}: {}",
                    userId, ex.getMessage(), ex);
            writeJson(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, STORE_UNAVAILABLE_BODY);
            return false;
        }
        if (user == null || user.getStatus() == null || user.getStatus() != 1) {
            return true;
        }
        int claimTv = jwtService.getTokenVersion(claims);
        int rowTv = user.getTokenVersion() == null ? 0 : user.getTokenVersion();
        if (claimTv != rowTv) {
            return true;
        }
        if (user.getMustChangePassword() != null && user.getMustChangePassword() == 1
                && !FORCED_CHANGE_EXEMPT.contains(request.getRequestURI())) {
            writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                    "{\"code\":403,\"message\":\"PASSWORD_CHANGE_REQUIRED\",\"data\":null}");
            return false;
        }
        AuthUser authUser = new AuthUser(user.getId(), user.getTenantId(), user.getUsername(), null);
        UserContext.set(authUser);
        var authentication = new UsernamePasswordAuthenticationToken(
                authUser, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return true;
    }

    private void writeJson(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
    }
}
