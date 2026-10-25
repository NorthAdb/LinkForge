package com.linkforge.link.support;

import com.linkforge.config.LinkForgeProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * B2: classifies the non-human jumps that get the exact same 302 as any GET but
 * record no click anywhere downstream — self-declared prefetch headers and a narrow
 * list of named preview bots. Everything else (curl, app WebViews, empty UAs, unknown
 * crawlers) counts and lands in the device=bot bucket as before.
 */
@Component
public class ClickFilter {

    public enum SkipReason {
        PREFETCH("prefetch"),
        PREVIEW_BOT("preview_bot");

        private final String tag;

        SkipReason(String tag) {
            this.tag = tag;
        }

        /** Bounded metric tag value: linkforge.click.skipped only ever carries these two. */
        public String tag() {
            return tag;
        }
    }

    private final boolean enabled;
    private final boolean prefetchHeadersEnabled;
    private final List<String> botTokens;

    public ClickFilter(LinkForgeProperties properties) {
        LinkForgeProperties.ClickFilter cfg = properties.getClickFilter();
        this.enabled = cfg.isEnabled();
        this.prefetchHeadersEnabled = cfg.isPrefetchHeadersEnabled();
        this.botTokens = cfg.getBotUaTokens().stream()
                .map(token -> token.toLowerCase(Locale.ROOT).trim())
                .filter(token -> !token.isBlank())
                .toList();
    }

    /** Empty = the jump counts. The filter never throws and never blocks a jump. */
    public Optional<SkipReason> classify(HttpServletRequest request) {
        if (!enabled) {
            return Optional.empty();
        }
        if (prefetchHeadersEnabled) {
            Optional<SkipReason> prefetch = classifyPrefetchHeaders(request);
            if (prefetch.isPresent()) {
                return prefetch;
            }
        }
        String ua = request.getHeader("User-Agent");
        if (ua == null || ua.isBlank()) {
            return Optional.empty();
        }
        String lower = ua.toLowerCase(Locale.ROOT);
        return botTokens.stream().anyMatch(lower::contains)
                ? Optional.of(SkipReason.PREVIEW_BOT)
                : Optional.empty();
    }

    /** MDN Sec-Purpose covers "prefetch" and "prefetch;prerender"; Purpose/X-Purpose/X-Moz
     * are the legacy Chrome/Firefox/Safari spellings. Tokens are matched exactly, never
     * as substrings, so "prefetchx" is an ordinary header. */
    private Optional<SkipReason> classifyPrefetchHeaders(HttpServletRequest request) {
        if (headerHasToken(request, "Sec-Purpose", "prefetch")
                || valueIs(request, "X-Moz", "prefetch")) {
            return Optional.of(SkipReason.PREFETCH);
        }
        String purpose = trimmed(request.getHeader("Purpose"));
        if ("prefetch".equalsIgnoreCase(purpose) || "preview".equalsIgnoreCase(purpose)) {
            return Optional.of(SkipReason.PREFETCH);
        }
        String xPurpose = trimmed(request.getHeader("X-Purpose"));
        if ("prefetch".equalsIgnoreCase(xPurpose) || "preview".equalsIgnoreCase(xPurpose)) {
            return Optional.of(SkipReason.PREFETCH);
        }
        return Optional.empty();
    }

    private static boolean headerHasToken(HttpServletRequest request, String name, String expected) {
        String value = request.getHeader(name);
        if (value == null) {
            return false;
        }
        for (String token : value.split("[;,]")) {
            if (expected.equalsIgnoreCase(token.trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean valueIs(HttpServletRequest request, String name, String expected) {
        String value = request.getHeader(name);
        return value != null && expected.equalsIgnoreCase(value.trim());
    }

    private static String trimmed(String value) {
        return value == null ? null : value.trim();
    }
}
