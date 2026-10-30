package com.linkforge.security;

import com.linkforge.link.support.ShareTokenSigner;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C1 wave B (D-C1-2): opens GET /api/links/{code}/qr.png ONLY for a request whose
 * exp+sig share token validates — there is deliberately no permitAll QR route. The
 * filter runs solely on signed material requests; on anything else (unsigned,
 * non-GET, other paths, a failed or expired token) it does nothing, so the usual
 * JWT/API-key auth still applies and anonymous traffic meets the standard 401.
 */
@Component
@RequiredArgsConstructor
public class QrShareAuthFilter extends OncePerRequestFilter {

    public static final String ROLE_QR_SHARE = "ROLE_QR_SHARE";
    private static final Pattern QR_PATH = Pattern.compile("^/api/links/([^/]+)/qr\\.png$");

    private final ShareTokenSigner shareTokenSigner;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) {
            return true;
        }
        return request.getParameter("exp") == null || request.getParameter("sig") == null
                || !QR_PATH.matcher(request.getRequestURI()).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Matcher path = QR_PATH.matcher(request.getRequestURI());
        long exp = parseEpoch(request.getParameter("exp"));
        if (!path.matches() || exp < 0
                || !shareTokenSigner.verify(path.group(1), exp, request.getParameter("sig"))) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            var principal = new QrSharePrincipal(path.group(1));
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority(ROLE_QR_SHARE))));
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** -1 marks a malformed epoch so verify is never reached with garbage. */
    private static long parseEpoch(String raw) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
