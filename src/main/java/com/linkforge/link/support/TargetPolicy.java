package com.linkforge.link.support;

import lombok.extern.slf4j.Slf4j;

import java.net.IDN;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * B9: pure verdict over a target URL against normalized domain lists. Matching is by
 * label-boundary suffix (evil.com matches a.evil.com but never notevil.com or
 * evil.com.cn), hosts are IDN-normalized so a punycode URL and its Unicode listing
 * hit the same entry, and BLOCK wins over WARN. This is a locally maintained list
 * gate — no reputation service, no content scan (D-Bw345-29).
 */
@Slf4j
public final class TargetPolicy {

    public enum Action { ALLOW, WARN, BLOCK }

    public enum Reason {
        BLOCK_LIST("block_list"), WARN_LIST("warn_list"), USERINFO("userinfo");

        private final String tag;

        Reason(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    public record Verdict(Action action, Reason reason) {
        public static final Verdict ALLOW = new Verdict(Action.ALLOW, null);
    }

    /** Both sets hold normalized (IDN ASCII, lowercase, no trailing dot) hosts. */
    public record Config(Set<String> blockDomains, Set<String> warnDomains) {
        public static final Config EMPTY = new Config(Set.of(), Set.of());
    }

    private TargetPolicy() {
    }

    /**
     * @param forCreate userinfo targets are rejected at create/update time (400) but
     *                  only warned for rows that already exist (D-Bw345-25)
     */
    public static Verdict evaluate(String url, Config config, boolean forCreate) {
        URI uri = parse(url);
        String host = hostOf(uri);
        if (host != null) {
            if (matchesAny(host, config.blockDomains())) {
                return new Verdict(Action.BLOCK, Reason.BLOCK_LIST);
            }
            if (matchesAny(host, config.warnDomains())) {
                return new Verdict(Action.WARN, Reason.WARN_LIST);
            }
        }
        if (uri != null && uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            return forCreate
                    ? new Verdict(Action.BLOCK, Reason.USERINFO)
                    : new Verdict(Action.WARN, Reason.USERINFO);
        }
        return Verdict.ALLOW;
    }

    /** ASCII/punycode host for display on the warn page; null when the URL has no host. */
    public static String asciiHost(String url) {
        return hostOf(parse(url));
    }

    private static URI parse(String url) {
        try {
            return URI.create(url);
        } catch (IllegalArgumentException ex) {
            log.debug("target policy cannot parse url: {}", ex.getMessage());
            return null;
        }
    }

    private static String hostOf(URI uri) {
        if (uri == null) {
            return null;
        }
        String host = uri.getHost();
        if (host == null) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        try {
            return IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException ex) {
            log.debug("host {} left unnormalized: {}", host, ex.getMessage());
            return host;
        }
    }

    /** List entries run through the same normalization pipeline as the URL host.
     * Entries are bare domains, so no URI parsing is involved (URI.create rejects
     * non-ASCII hosts, but IDN handles them directly). */
    public static String normalizeEntry(String entry) {
        String host = entry.trim().toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        try {
            return IDN.toASCII(host, IDN.ALLOW_UNASSIGNED);
        } catch (IllegalArgumentException ex) {
            log.debug("list entry {} left unnormalized: {}", host, ex.getMessage());
            return host;
        }
    }

    private static boolean matchesAny(String host, Set<String> entries) {
        for (String entry : entries) {
            if (host.equals(entry) || host.endsWith("." + entry)) {
                return true;
            }
        }
        return false;
    }
}
