package com.linkforge.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 3b: the ab-report endpoint over preset variant buckets. Buckets are
 * inserted straight into lf_click_dim_daily (the table the report reads), so
 * each verdict tier is exercised deterministically — no click traffic needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-abreport:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=200",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class AbReportIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> seededLinkIds = new ArrayList<>();

    @AfterEach
    void cleanSeededBuckets() {
        for (Long linkId : seededLinkIds) {
            jdbc.update("DELETE FROM lf_click_dim_daily WHERE link_id = ?", linkId);
        }
        seededLinkIds.clear();
    }

    private String createVariantLink(String token, int weightA, int weightB) throws Exception {
        String json = om.writeValueAsString(Map.of(
                "originalUrl", "https://example.com/ab-" + unique(""),
                "variants", List.of(
                        Map.of("key", "A", "url", "https://a.example.com/" + unique(""), "weight", weightA),
                        Map.of("key", "B", "url", "https://b.example.com/" + unique(""), "weight", weightB))));
        var resp = postJson("/api/links", json, token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        return body(resp).path("data").path("code").asText();
    }

    private void seedBucket(String code, String variantKey, long clicks) {
        Long linkId = jdbc.queryForObject("SELECT id FROM lf_short_link WHERE code = ?", Long.class, code);
        Long tenantId = jdbc.queryForObject("SELECT tenant_id FROM lf_short_link WHERE code = ?", Long.class, code);
        jdbc.update("INSERT INTO lf_click_dim_daily (tenant_id, link_id, stat_date, dim_type, dim_value, clicks) "
                        + "VALUES (?, ?, ?, 'variant', ?, ?)",
                tenantId, linkId, LocalDate.now(), variantKey, clicks);
        if (!seededLinkIds.contains(linkId)) {
            seededLinkIds.add(linkId);
        }
    }

    private JsonNode report(String token, String code) throws Exception {
        var resp = get("/api/links/" + code + "/stats/ab-report?days=7", token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        return body(resp).path("data");
    }

    private static JsonNode rowOf(JsonNode data, String key) {
        for (JsonNode row : data.path("variants")) {
            if (key.equals(row.path("key").asText())) {
                return row;
            }
        }
        throw new AssertionError("no row for variant " + key + " in " + data);
    }

    @Test
    void uniformDrawReportsConsistent() throws Exception {
        String token = registerForToken();
        String code = createVariantLink(token, 1, 1);
        seedBucket(code, "A", 50);
        seedBucket(code, "B", 50);

        JsonNode data = report(token, code);
        assertEquals("CONSISTENT", data.path("verdict").asText());
        assertEquals(100, data.path("totalClicks").asLong());
        assertEquals(0.0, data.path("chiSquare").asDouble(), 1e-9);
        assertEquals(1.0, data.path("pValue").asDouble(), 1e-9);
        assertEquals(50.0, rowOf(data, "A").path("expected").asDouble(), 1e-9);
        assertEquals(0.5, rowOf(data, "B").path("share").asDouble(), 1e-9);
    }

    @Test
    void lopsidedDrawReportsSkewed() throws Exception {
        String token = registerForToken();
        String code = createVariantLink(token, 1, 1);
        seedBucket(code, "A", 95);
        seedBucket(code, "B", 5);

        JsonNode data = report(token, code);
        assertEquals("SKEWED", data.path("verdict").asText());
        assertTrue(data.path("pValue").asDouble() < 0.05);
    }

    @Test
    void thinDataReportsInsufficient() throws Exception {
        String token = registerForToken();
        String code = createVariantLink(token, 1, 1);
        seedBucket(code, "A", 10);
        seedBucket(code, "B", 5);

        JsonNode data = report(token, code);
        assertEquals("INSUFFICIENT_DATA", data.path("verdict").asText());
        assertTrue(data.path("chiSquare").isNull());
        assertTrue(data.path("pValue").isNull());
    }

    @Test
    void plainLinkReportsNotApplicable() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/ab-plain-" + unique(""));

        JsonNode data = report(token, code);
        assertEquals("NOT_APPLICABLE", data.path("verdict").asText());
        assertEquals(0, data.path("variants").size());
    }

    @Test
    void deletedVariantBucketIsOrphanedOutsideTheFit() throws Exception {
        String token = registerForToken();
        String code = createVariantLink(token, 1, 1);
        seedBucket(code, "A", 50);
        seedBucket(code, "B", 50);
        seedBucket(code, "C", 20); // C was deleted from the configuration after the clicks

        JsonNode data = report(token, code);
        assertEquals("CONSISTENT", data.path("verdict").asText());
        assertEquals(100, data.path("totalClicks").asLong());
        assertEquals(3, data.path("variants").size());
        JsonNode orphan = rowOf(data, "C");
        assertTrue(orphan.path("orphaned").asBoolean());
        assertTrue(orphan.path("weight").isNull());
        assertTrue(orphan.path("expected").isNull());
        assertEquals(20, orphan.path("observed").asLong());
    }

    @Test
    void reportIsOwnerOnly() throws Exception {
        String owner = registerForToken();
        String code = createVariantLink(owner, 1, 1);
        String stranger = registerForToken();
        var resp = get("/api/links/" + code + "/stats/ab-report", stranger);
        assertEquals(404, resp.getStatusCode().value(), resp.getBody());
    }
}
