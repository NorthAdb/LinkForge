package com.linkforge.link.support;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Batch 3b: parses a URLhaus-style host feed into normalized hosts. The
 * canonical input is the URLhaus "hostfile" download (hosts-file lines:
 * {@code 127.0.0.1<TAB>evil.example}); plain one-host-per-line lists and full
 * URL lines are accepted too, so the parser survives a feed-format switch.
 * Comment ({@code #}) and blank lines are skipped, as are the hosts-file
 * sinkhole/loopback names themselves. Every surviving entry runs through the
 * {@link TargetPolicy#normalizeEntry} pipeline, so a feed entry and a URL host
 * always meet in the same normalized form. Pure function, unit-tested.
 */
public final class MaliciousFeedParser {

    /** Post-normalization sanity gate: DNS labels of letters/digits/hyphens/dots. */
    private static final Pattern HOST_SHAPE = Pattern.compile("[a-z0-9]([a-z0-9.-]*[a-z0-9])?");
    private static final Set<String> NEVER_HOSTS = Set.of("localhost", "127.0.0.1", "::1", "0.0.0.0");

    private MaliciousFeedParser() {
    }

    public static Set<String> parse(String text) {
        Set<String> hosts = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return hosts;
        }
        for (String raw : text.split("\\R")) {
            String host = hostOfLine(raw.trim());
            if (host != null) {
                hosts.add(host);
            }
        }
        return hosts;
    }

    private static String hostOfLine(String line) {
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
            return null;
        }
        // hosts-file shape: "<ip> <host> [aliases...]" — the host is the second
        // token when the first token looks like an IP literal.
        String[] tokens = line.split("\\s+");
        String candidate;
        if (tokens.length >= 2 && looksLikeIp(tokens[0])) {
            candidate = tokens[1];
        } else {
            candidate = tokens[0];
        }
        // URL line: keep only the authority's host.
        if (candidate.contains("://")) {
            try {
                String host = URI.create(candidate).getHost();
                if (host == null) {
                    return null;
                }
                candidate = host;
            } catch (IllegalArgumentException ex) {
                return null;
            }
        } else {
            int pathAt = candidate.indexOf('/');
            if (pathAt >= 0) {
                candidate = candidate.substring(0, pathAt);
            }
            int portAt = candidate.lastIndexOf(':');
            if (portAt > 0 && candidate.indexOf(':') == portAt) {
                candidate = candidate.substring(0, portAt);
            }
        }
        String normalized = TargetPolicy.normalizeEntry(candidate);
        if (normalized.isBlank() || NEVER_HOSTS.contains(normalized)
                || normalized.contains("..") || !HOST_SHAPE.matcher(normalized).matches()) {
            return null;
        }
        return normalized;
    }

    private static boolean looksLikeIp(String token) {
        if (token.indexOf(':') >= 0) {
            return true; // IPv6 literal in the sinkhole column
        }
        int dots = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c == '.') {
                dots++;
            } else if (!Character.isDigit(c)) {
                return false;
            }
        }
        return dots == 3;
    }
}
