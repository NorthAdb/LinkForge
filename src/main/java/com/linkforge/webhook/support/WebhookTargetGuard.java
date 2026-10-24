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
            String violation = violationOf(address);
            if (violation != null) {
                return host + " resolves to " + address.getHostAddress() + " (" + violation + ")";
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
            // CGNAT 100.64.0.0/10
            if ((bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64) {
                return "shared address space (CGNAT)";
            }
            return null;
        }
        // IPv6: ULA fc00::/7, and IPv4-mapped addresses fall back to the v4 rules
        boolean ipv4Mapped = bytes[0] == 0 && bytes[1] == 0
                && Arrays.equals(Arrays.copyOfRange(bytes, 2, 12), new byte[10])
                && bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF;
        if (ipv4Mapped) {
            try {
                InetAddress v4 = InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16));
                return violationOf(v4);
            } catch (UnknownHostException ex) {
                return "unparseable ipv4-mapped address";
            }
        }
        if ((bytes[0] & 0xFE) == 0xFC) {
            return "unique local address (fc00::/7)";
        }
        return null;
    }

    InetAddress[] resolveHost(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }
}
