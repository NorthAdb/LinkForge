package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B9: a local domain blocklist/warnlist gates create/update and every redirect.
 * BLOCK = hard 403 interstitial with no target; WARN = 200 interstitial showing the
 * escaped target, ?lf_continue=1 proceeds (and that 302 counts). Rows that predate a
 * list change are re-checked at redirect time — seeded through the API (so Bloom knows
 * the code) with the target rewritten straight in the DB.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PhishingGateIT extends AbstractIntegrationTest {

    static final Path BLOCKLIST;

    static {
        try {
            BLOCKLIST = Files.createTempFile("lf-blocklist-it", ".txt");
            Files.writeString(BLOCKLIST, "# nothing on the file yet\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void phishingProperties(DynamicPropertyRegistry registry) {
        registry.add("linkforge.phishing.block-domains", () -> "blocked.invalid");
        registry.add("linkforge.phishing.warn-domains", () -> "warn.invalid");
        registry.add("linkforge.phishing.blocklist-file", () -> BLOCKLIST.toString());
        registry.add("linkforge.phishing.reload-interval-ms", () -> "600000");
        registry.add("linkforge.rate-limit.key-prefix", () -> "linkforge:rl:it-phish:");
        registry.add("linkforge.rate-limit.register-max", () -> "1000");
        registry.add("linkforge.rate-limit.create-max", () -> "200");
        registry.add("linkforge.rate-limit.redirect-max", () -> "1000");
    }

    @Autowired
    private JdbcTemplate jdbc;

    /** Creates a link through the API, then rewrites its target in the DB — the row is
     * Bloom-visible and its URL bypasses create-time validation. */
    private String seedWithTarget(String finalUrl) throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/seed-" + unique(""));
        jdbc.update("UPDATE lf_short_link SET original_url = ? WHERE code = ?", finalUrl, code);
        return code;
    }

    private long clickRows(String code) {
        Long n = jdbc.queryForObject("SELECT COUNT(*) FROM lf_click_log WHERE code = ?", Long.class, code);
        return n == null ? 0 : n;
    }

    @Test
    void createToBlockedDomainIsRejectedWithoutEchoingIt() throws Exception {
        String token = registerForToken();
        var resp = postJson("/api/links",
                om.writeValueAsString(java.util.Map.of("originalUrl", "https://x.blocked.invalid/a")), token);
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
        assertFalse(resp.getBody().contains("blocked.invalid"),
                "the rejection must not echo the listed domain");
    }

    @Test
    void createToWarnDomainIsAllowed() throws Exception {
        String token = registerForToken();
        var resp = postJson("/api/links",
                om.writeValueAsString(java.util.Map.of("originalUrl", "https://warn.invalid/ok")), token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
    }

    @Test
    void createWithUserinfoTargetIsRejected() throws Exception {
        String token = registerForToken();
        var resp = postJson("/api/links",
                om.writeValueAsString(java.util.Map.of("originalUrl", "https://user:pass@example.com/steal")), token);
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
    }

    @Test
    void pathWithAtIsNotUserinfo() throws Exception {
        String token = registerForToken();
        var resp = postJson("/api/links",
                om.writeValueAsString(java.util.Map.of("originalUrl", "https://example.com/path@x")), token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
    }

    @Test
    void redirectOfBlockedRowIsHardBlocked() throws Exception {
        String code = seedWithTarget("https://x.blocked.invalid/target");
        var resp = get("/r/" + code, null);
        assertEquals(403, resp.getStatusCode().value(), resp.getBody());
        assertNull(resp.getHeaders().getLocation(), "a blocked jump must not carry a Location");
        assertNotNull(resp.getHeaders().getCacheControl());
        assertTrue(resp.getHeaders().getCacheControl().contains("no-store"));
        assertFalse(resp.getBody().contains("blocked.invalid"), "page must not show the target");
        assertEquals(0, clickRows(code), "a blocked jump records no click");
    }

    @Test
    void redirectOfWarnRowShowsInterstitial() throws Exception {
        // Raw < > " cannot pass isRenderableUrl (410) — URI.create rejects them — so the
        // escaping proof uses the HTML-dangerous chars that ARE legal in a URI: ' and &.
        String code = seedWithTarget("https://warn.invalid/?q='quoted'&r=2");
        var resp = get("/r/" + code, null);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        assertNull(resp.getHeaders().getLocation());
        assertTrue(resp.getBody().contains("&#39;quoted&#39;"), "single quotes must be escaped: " + resp.getBody());
        assertFalse(resp.getBody().contains("'quoted'"), "no raw quote may leak");
        assertTrue(resp.getBody().contains("&amp;r=2"), "& must be escaped");
        assertFalse(resp.getBody().contains("&r=2"), "no raw ampersand may leak");
        assertTrue(resp.getBody().contains("/r/" + code + "?lf_continue=1"), "one explicit continue link");
        assertEquals("no-referrer", resp.getHeaders().getFirst("Referrer-Policy"));
        assertNotNull(resp.getHeaders().getFirst("Content-Security-Policy"));
        assertEquals(0, clickRows(code), "the interstitial itself records no click");

        var head = head("/r/" + code);
        assertEquals(200, head.getStatusCode().value());
        assertNull(head.getHeaders().getLocation(), "HEAD of a warn row must not look jumpable");
    }

    @Test
    void continueParameterSkipsOnlyTheWarnGateAndCounts() throws Exception {
        String code = seedWithTarget("https://warn.invalid/continue");
        long before = clickRows(code);
        var resp = get("/r/" + code + "?lf_continue=1", null);
        assertEquals(302, resp.getStatusCode().value(), resp.getBody());
        assertEquals(before + 1, clickRows(code), "the real navigation after continue counts");
    }

    @Test
    void headOfBlockedRowIs403() throws Exception {
        String code = seedWithTarget("https://x.blocked.invalid/head");
        var resp = head("/r/" + code);
        assertEquals(403, resp.getStatusCode().value());
        assertNull(resp.getHeaders().getLocation());
    }

    @Test
    void gateRunsBeforeTheBotSkip() throws Exception {
        String code = seedWithTarget("https://x.blocked.invalid/bot");
        var resp = getWithHeader("/r/" + code, null, "User-Agent",
                "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)");
        assertEquals(403, resp.getStatusCode().value(),
                "a blocked target must 403 even for a preview bot");
    }

    @Test
    void unlistedDomainsAreUnaffected() throws Exception {
        String token = registerForToken();
        var resp = postJson("/api/links",
                om.writeValueAsString(java.util.Map.of("originalUrl", "https://evil.example.com/just-a-name")), token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
    }
}
