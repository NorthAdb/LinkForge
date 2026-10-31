package com.linkforge.webhook.support;

import com.linkforge.common.exception.BizException;
import com.linkforge.config.LinkForgeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;

/**
 * B-wave2 D-Bw2-15: SSRF guard for webhook targets, applied at save time AND at every
 * send, where {@link #resolveForSend(String)} resolves the host exactly once and the
 * sender pins the connection to those addresses only — the resolve-then-connect TOCTOU
 * (DNS rebinding, D-C4-1) is closed. Rules: only http/https, no userinfo, and every
 * resolved IP of the host must be public — loopback, any-local, multicast,
 * link-local (incl. cloud metadata 169.254.169.254), site-local, CGNAT 100.64/10
 * and IPv6 ULA fc00::/7 are blocked. Redirects are never followed by the sender.
 *
 * Honest limits (README): port whitelisting and egress-proxy isolation are out of
 * scope this wave.
 */
@Component
@RequiredArgsConstructor
public class WebhookTargetGuard {

    private final LinkForgeProperties properties;

    /** One send-time resolution: either a violation reason or the addresses to pin the connection to. */
    public record ResolvedAddresses(String violationReason, InetAddress[] pinned) {

        static ResolvedAddresses blocked(String reason) {
            return new ResolvedAddresses(reason, null);
        }

        static ResolvedAddresses pinned(InetAddress[] addresses) {
            return new ResolvedAddresses(null, addresses);
        }
    }

    /** Save-time check: raises 400 on a blocked target. */
    public void check(String url) {
        String reason = violationOf(url);
        if (reason != null) {
            throw new BizException(400, "webhook target not allowed: " + reason);
        }
    }

    /**
     * Send-time resolution: the host is resolved ONCE here and the returned addresses are
     * the only ones the sender may connect to, so DNS cannot be flipped between validation
     * and connection. Reason strings are the same ones {@link #check(String)} echoes.
     */
    public ResolvedAddresses resolveForSend(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            return ResolvedAddresses.blocked("unparseable url");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return ResolvedAddresses.blocked("scheme must be http or https");
        }
        if (uri.getUserInfo() != null) {
            return ResolvedAddresses.blocked("userinfo is not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return ResolvedAddresses.blocked("missing host");
        }
        InetAddress[] addresses;
        try {
            addresses = resolveHost(host);
        } catch (UnknownHostException ex) {
            return ResolvedAddresses.blocked("host does not resolve");
        }
        if (properties.getWebhook().isAllowPrivateTargets()) {
            // IT-only escape hatch; never true in prod
            return ResolvedAddresses.pinned(addresses);
        }
        for (InetAddress address : addresses) {
            if (violationOf(address) != null) {
                // Bw2-N6: the reason must not echo resolved addresses — one uniform reason
                return ResolvedAddresses.blocked("blocked target");
            }
        }
        return ResolvedAddresses.pinned(addresses);
    }

    /** Null when the target is acceptable, otherwise a short violation reason. */
    String violationOf(String url) {
        return resolveForSend(url).violationReason();
    }

    String violationOf(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isMulticastAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()) {
            return "private or special-purpose address";
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            // 0.0.0.0/8 ("this network"), CGNAT 100.64.0.0/10, benchmark 198.18.0.0/15,
            // reserved 240.0.0.0/4 (incl. the broadcast 255.255.255.255)
            if (bytes[0] == 0) {
                return "this-network block (0.0.0.0/8)";
            }
            if ((bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64) {
                return "shared address space (CGNAT)";
            }
            if ((bytes[0] & 0xFF) == 198 && (bytes[1] & 0xFE) == 18) {
                return "benchmarking block (198.18.0.0/15)";
            }
            if ((bytes[0] & 0xF0) == 0xF0) {
                return "reserved block (240.0.0.0/4)";
            }
            return null;
        }
        // IPv6 with an embedded IPv4 falls back to the v4 rules; ULA fc00::/7 is blocked
        byte[] embedded = embeddedIpv4(bytes);
        if (embedded != null) {
            try {
                return violationOf(InetAddress.getByAddress(embedded));
            } catch (UnknownHostException ex) {
                return "unparseable embedded ipv4";
            }
        }
        if ((bytes[0] & 0xFE) == 0xFC) {
            return "unique local address (fc00::/7)";
        }
        return null;
    }

    /**
     * Extracts the IPv4 embedded in ::ffff:0:0/96 (mapped), ::/96 (compatible),
     * 64:ff9b::/96 (NAT64) and 2002::/16 (6to4); null when the address embeds none.
     */
    private static byte[] embeddedIpv4(byte[] bytes) {
        boolean allZeroPrefix = true;
        for (int i = 0; i < 12; i++) {
            if (bytes[i] != 0) {
                allZeroPrefix = false;
                break;
            }
        }
        if (allZeroPrefix) {
            // JDK already folds ::ffff:a.b.c.d into an Inet4Address, so this path only
            // sees the ::/96-compatible form; either way the trailing 4 bytes are the IPv4.
            return Arrays.copyOfRange(bytes, 12, 16);
        }
        if (bytes[0] == 0x00 && bytes[1] == 0x64 && bytes[2] == (byte) 0xFF && bytes[3] == (byte) 0x9B
                && bytes[4] == 0 && bytes[5] == 0 && bytes[6] == 0 && bytes[7] == 0
                && bytes[8] == 0 && bytes[9] == 0 && bytes[10] == 0 && bytes[11] == 0) {
            return Arrays.copyOfRange(bytes, 12, 16); // 64:ff9b::a.b.c.d
        }
        if (bytes[0] == 0x20 && bytes[1] == 0x02) {
            return new byte[]{bytes[2], bytes[3], bytes[4], bytes[5]}; // 2002:a.b.c.d::/16
        }
        return null;
    }

    InetAddress[] resolveHost(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }
}
