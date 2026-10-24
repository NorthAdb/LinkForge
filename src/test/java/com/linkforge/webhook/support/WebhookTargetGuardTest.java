package com.linkforge.webhook.support;

import com.linkforge.common.exception.BizException;
import com.linkforge.config.LinkForgeProperties;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookTargetGuardTest {

    private static final String PUBLIC_IP = "93.184.216.34"; // literal, no DNS

    @Test
    void schemeAndUserinfoRulesAlwaysApply() {
        WebhookTargetGuard guard = guard(new LinkForgeProperties(), PUBLIC_IP);
        assertBlocked(guard, "ftp://example.com/hook");
        assertBlocked(guard, "http://user:pass@example.com/hook");
        assertBlocked(guard, "http://user:pass@10.0.0.1/hook");
        assertNotBlocked(guard, "https://example.com/hook");
        assertNotBlocked(guard, "http://example.com/hook");
    }

    @Test
    void privateAndSpecialPurposeIpsAreBlocked() throws Exception {
        String[] blocked = {"127.0.0.1", "10.0.0.7", "192.168.1.1", "172.16.5.5",
                "169.254.169.254", "100.64.0.13", "0.0.0.0", "::1", "fe80::1", "fd00::5", "ff02::1"};
        for (String ip : blocked) {
            WebhookTargetGuard guard = guard(new LinkForgeProperties(), ip);
            assertTrue(guard.isBlocked("http://target.test/hook"),
                    "expected blocked: " + ip + " -> " + guard.violationOf("http://target.test/hook"));
        }
    }

    @Test
    void ipv4MappedIpv6FallsBackToV4Rules() throws Exception {
        InetAddress mapped = InetAddress.getByName("::ffff:127.0.0.1");
        WebhookTargetGuard guard = guard(new LinkForgeProperties(), mapped.getHostAddress());
        assertTrue(guard.isBlocked("http://target.test/hook"),
                "::ffff:127.0.0.1 must not slip through as IPv6");
    }

    @Test
    void allowPrivateTargetsEscapeHatchOnlySkipsIpChecks() {
        LinkForgeProperties properties = new LinkForgeProperties();
        properties.getWebhook().setAllowPrivateTargets(true);
        WebhookTargetGuard guard = guard(properties, "127.0.0.1");

        assertNotBlocked(guard, "http://127.0.0.1:9/hook"); // the IT stub case
        assertBlocked(guard, "ftp://example.com/hook");      // scheme rules stay unconditional
        assertBlocked(guard, "http://user:pass@127.0.0.1/hook");
    }

    @Test
    void unresolvableHostsAreBlocked() {
        WebhookTargetGuard guard = new WebhookTargetGuard(new LinkForgeProperties()) {
            @Override
            InetAddress[] resolveHost(String host) throws UnknownHostException {
                throw new UnknownHostException(host);
            }
        };
        assertBlocked(guard, "https://no-such-host.invalid/hook");
    }

    @Test
    void checkRaisesBizExceptionOnBlockedTarget() {
        WebhookTargetGuard guard = guard(new LinkForgeProperties(), "127.0.0.1");
        assertThrows(BizException.class, () -> guard.check("http://loopback.test/hook"));
        assertDoesNotThrow(() -> guard.check("https://public.test/hook"));
    }

    private WebhookTargetGuard guard(LinkForgeProperties properties, String... answers) {
        return new WebhookTargetGuard(properties) {
            @Override
            InetAddress[] resolveHost(String host) throws UnknownHostException {
                // hosts named public* resolve to a literal public IP, the rest to the canned answers
                String[] picked = host.startsWith("public") ? new String[]{PUBLIC_IP} : answers;
                InetAddress[] addresses = new InetAddress[picked.length];
                for (int i = 0; i < picked.length; i++) {
                    addresses[i] = InetAddress.getByName(picked[i]); // literal IPs: no DNS
                }
                return addresses;
            }
        };
    }

    private void assertBlocked(WebhookTargetGuard guard, String url) {
        assertTrue(guard.isBlocked(url), "expected blocked: " + url);
    }

    private void assertNotBlocked(WebhookTargetGuard guard, String url) {
        assertFalse(guard.isBlocked(url), "expected allowed: " + url);
    }
}
