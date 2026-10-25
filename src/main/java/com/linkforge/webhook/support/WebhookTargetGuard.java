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
 * B-wave2 D-Bw2-15: basic SSRF guard for webhook targets, applied at save time
 * AND re-checked before every send. Rules: only http/https, no userinfo, and every
 * resolved IP of the host must be public — loopback, any-local, multicast,
 * link-local (incl. cloud metadata 169.254.169.254), site-local, CGNAT 100.64/10
 * and IPv6 ULA fc00::/7 are blocked. Redirects are never followed by the relay.
 *
 * Honest limits (README): the resolve-then-connect TOCTOU (DNS rebinding) is NOT
 * solved here — the relay's HttpClient does its own resolution; port whitelisting
 * and egress-proxy isolation are out of scope this wave.
 */
@Component
@RequiredArgsConstructor
public class WebhookTargetGuard {

    private final LinkForgeProperties properties;

    /** Save-time check: raises 400 on a blocked target. */
    public void check(String url) {
        String reason = violationOf(url);
        if (reason != null) {
            throw new BizException(400, "webhook target not allowed: " + reason);
        }
    }

    /** Send-time check: true when the target must not be contacted. */
    public boolean isBlocked(String url) {
        return violationOf(url) != null;
    }

    /** Null when the target is acceptable, otherwise a short violation reason. */
    String violationOf(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException ex) {
            return "unparseable url";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "scheme must be http or https";
        }
        if (uri.getUserInfo() != null) {
            return "userinfo is not allowed";
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return "missing host";
        }
        if (properties.getWebhook().isAllowPrivateTargets()) {
            return null; // IT-only escape hatch; never true in prod
        }
        InetAddress[] addresses;
        try {
            addresses = resolveHost(host);
        } catch (UnknownHostException ex) {
            return "host does not resolve";
        }
        for (InetAddress address : addresses) {
            if (violationOf(address) != null) {
                // Bw2-N6: the 400 body must not echo resolved addresses — one uniform reason
                return "blocked target";
            }
        }
        return null;
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
