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
 * hit the same entry, and BLOCK wins over WARN. This is a list gate — no reputation
 * service, no content scan (D-Bw345-29). Batch 3b adds a third list: hosts imported
 * from the malicious-host feed (URLhaus) into a local table; the verdict is still
 * computed purely locally against the in-memory snapshot, never by calling out.
 */
@Slf4j
public final class TargetPolicy {

    public enum Action { ALLOW, WARN, BLOCK }

    public enum Reason {
        BLOCK_LIST("block_list"), WARN_LIST("warn_list"), USERINFO("userinfo"),
        /** Batch 3b: matched the imported malicious-host feed, not a local list. */
        MALICIOUS_FEED("malicious_feed");

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

    /** All sets hold normalized (IDN ASCII, lowercase, no trailing dot) hosts.
     * {@code feedDomains} is the imported malicious-host feed snapshot (batch 3b);
     * it BLOCKs like the local block list but reports its own reason. */
    public record Config(Set<String> blockDomains, Set<String> warnDomains, Set<String> feedDomains) {
        public static final Config EMPTY = new Config(Set.of(), Set.of(), Set.of());

        /** Pre-3b shape, kept so existing call sites and tests compile unchanged. */
        public Config(Set<String> blockDomains, Set<String> warnDomains) {
            this(blockDomains, warnDomains, Set.of());
        }

        public Config {
            if (feedDomains == null) {
                feedDomains = Set.of();
            }
        }
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
            // Batch 3b: the feed BLOCKs too, and beats WARN — but a host on the
            // local block list keeps the block_list attribution (checked first).
            if (matchesAny(host, config.feedDomains())) {
                return new Verdict(Action.BLOCK, Reason.MALICIOUS_FEED);
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
