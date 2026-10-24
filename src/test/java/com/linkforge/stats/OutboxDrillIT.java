package com.linkforge.stats;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.config.LinkForgeProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A7 acceptance in one context: the outbox transaction decouples redirects from MQ,
 * the relay drains PENDING rows, and with the sync write off delivery loss is
 * impossible (aggregate AND detail converge). The broker-outage retry leg lives in
 * scripts/outbox-drill.sh on the real stack: sibling cached contexts in this suite
 * run their own healthy relays against the shared MySQL, so a publisher injected to
 * fail here would be raced by them. Relay/sync flags are toggled at runtime through
 * the properties bean.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-outbox:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=200",
        "linkforge.rate-limit.redirect-max=1000",
        "linkforge.mq.async-click-enabled=true",
        "linkforge.mq.relay-fixed-delay-ms=500",
        "linkforge.mq.relay-initial-delay-ms=1000",
        "spring.rabbitmq.listener.simple.auto-startup=true"
})
@ActiveProfiles("test")
class OutboxDrillIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LinkForgeProperties properties;

    @AfterEach
    void restoreFlags() {
        properties.getMq().setRelayEnabled(true);
        properties.getMq().setSyncClickEnabled(true);
    }

    private long outboxCount(String status, String code) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM lf_outbox WHERE status = ? AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.code')) = ?",
                Long.class, status, code);
    }

    /**
     * Status-agnostic on purpose: sibling cached contexts run their own healthy
     * relays against the shared MySQL and may flip rows to SENT between our
     * redirects and the assert, so an exact-PENDING check is inherently racy.
     */
    private long outboxTotal(String code) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM lf_outbox WHERE JSON_UNQUOTE(JSON_EXTRACT(payload, '$.code')) = ?",
                Long.class, code);
    }

    private long clickLogCount(String code) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM lf_click_log WHERE code = ?", Long.class, code);
    }

    private long totalClicks(String token, String code) throws Exception {
        return body(get("/api/links/" + code + "/stats", token)).path("data").path("totalClicks").asLong();
    }

    @Test
    void sameTransactionWritesDetailAndPendingOutboxWithRelayOff() throws Exception {
        properties.getMq().setRelayEnabled(false);
        String token = registerForToken();
        String code = createLink(token, "https://example.com/outbox-tx");

        for (int i = 0; i < 3; i++) {
            assertEquals(302, get("/r/" + code, null).getStatusCode().value());
        }
        // sync detail and outbox row landed together (same transaction), regardless
        // of whether a sibling context's relay already flipped some rows to SENT
        assertEquals(3, clickLogCount(code));
        assertEquals(3, outboxTotal(code));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM lf_outbox WHERE JSON_UNQUOTE(JSON_EXTRACT(payload, '$.code')) = ? "
                        + "AND event_id NOT LIKE 'log-%'", Long.class, code),
                "sync on keeps deterministic log-{id} eventIds");

        properties.getMq().setRelayEnabled(true);
        String tok = token;
        await().atMost(java.time.Duration.ofSeconds(30))
                .untilAsserted(() -> assertEquals(3, outboxCount("SENT", code)));
        await().atMost(java.time.Duration.ofSeconds(30))
                .untilAsserted(() -> assertEquals(3, totalClicks(tok, code)));
        assertEquals(3, clickLogCount(code), "log-{id} events must not duplicate the sync detail");
    }

    @Test
    void syncOffBrokerDownStill302AndConvergesWithDetailAndUv() throws Exception {
        properties.getMq().setSyncClickEnabled(false);
        properties.getMq().setRelayEnabled(false);
        String token = registerForToken();
        String code = createLink(token, "https://example.com/outbox-sync-off");

        for (int i = 0; i < 4; i++) {
            assertEquals(302, get("/r/" + code, null).getStatusCode().value());
        }
        assertEquals(0, clickLogCount(code), "sync off writes no detail on the hot path");
        assertEquals(4, outboxTotal(code));
        assertEquals(4, jdbc.queryForObject(
                "SELECT COUNT(*) FROM lf_outbox WHERE JSON_UNQUOTE(JSON_EXTRACT(payload, '$.code')) = ? "
                        + "AND event_id NOT LIKE 'log-%'", Long.class, code),
                "sync off mints stable UUID eventIds in the outbox");

        properties.getMq().setRelayEnabled(true);
        String tok = token;
        await().atMost(java.time.Duration.ofSeconds(30))
                .untilAsserted(() -> {
                    assertEquals(4, outboxCount("SENT", code));
                    assertEquals(4, totalClicks(tok, code));
                });
        await().atMost(java.time.Duration.ofSeconds(30))
                .untilAsserted(() -> assertEquals(4, clickLogCount(code),
                        "consumer must backfill the detail for UUID events"));
        var stats = body(get("/api/links/" + code + "/stats", token));
        assertTrue(stats.path("data").path("totalUv").asLong() >= 1, "UV converges from consumer detail");
    }
}
