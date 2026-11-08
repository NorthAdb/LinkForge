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
import java.time.LocalDateTime;
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

    /**
     * S/N wave (audit S1) fixture migration: the applicability gate answers
     * NOT_APPLICABLE for any configuration it cannot PROVE stable over the
     * whole window, and a just-created link's configuration is provable
     * neither way (born inside the window). Fit-verdict fixtures therefore
     * backdate the install instant — the link row and the whole variant set
     * together, exactly the stamps ShortLinkService wrote at creation — so
     * the configuration predates the 7-day window. This mirrors production:
     * an established link older than the window. Assertions below are
     * unchanged; only the fixtures gained a past.
     */
    private void backdateConfig(String code, long days) {
        LocalDateTime installed = LocalDateTime.now().minusDays(days);
        jdbc.update("UPDATE lf_short_link SET created_at = ? WHERE code = ?", installed, code);
        jdbc.update("UPDATE lf_link_variant SET created_at = ? WHERE link_id = "
                + "(SELECT id FROM lf_short_link WHERE code = ?)", installed, code);
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
        backdateConfig(code, 30);
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
        backdateConfig(code, 30);
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
        backdateConfig(code, 30);
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
        assertEquals("TOO_FEW_VARIANTS", data.path("reason").asText());
        assertEquals(0, data.path("variants").size());
    }

    /**
     * S/N wave (audit S1) re-pin: this fixture used to assert CONSISTENT with
     * the orphan bucket excluded from the fit. Under the applicability gate
     * that reading is no longer available — an in-window bucket of a variant
     * that is no longer configured PROVES the configuration changed inside
     * the window, so the report must refuse the fit. The orphan ROW contract
     * is unchanged and asserted in full below (the fit-population exclusion
     * itself stays unit-pinned in AbReportTest). Nothing here was weakened:
     * the verdict assertion moved to the stricter contract.
     */
    @Test
    void deletedVariantBucketProvesConfigChangedInWindow() throws Exception {
        String token = registerForToken();
        String code = createVariantLink(token, 1, 1);
        backdateConfig(code, 30);
        seedBucket(code, "A", 50);
        seedBucket(code, "B", 50);
        seedBucket(code, "C", 20); // C was deleted from the configuration after the clicks

        JsonNode data = report(token, code);
        assertEquals("NOT_APPLICABLE", data.path("verdict").asText());
        assertEquals("CONFIG_CHANGED_IN_WINDOW", data.path("reason").asText());
        assertTrue(data.path("pValue").isNull());
        assertEquals(100, data.path("totalClicks").asLong());
        assertEquals(3, data.path("variants").size());
        JsonNode orphan = rowOf(data, "C");
        assertTrue(orphan.path("orphaned").asBoolean());
        assertTrue(orphan.path("weight").isNull());
        assertTrue(orphan.path("expected").isNull());
        assertEquals(20, orphan.path("observed").asLong());
    }

    @Test
    void conditionalRoutingIsNotApplicable() throws Exception {
        String token = registerForToken();
        String json = om.writeValueAsString(Map.of(
                "originalUrl", "https://example.com/ab-ruled-" + unique(""),
                "variants", List.of(
                        Map.of("key", "A", "url", "https://a.example.com/" + unique(""), "weight", 1,
                                "matchRule", "{\"devices\":[\"desktop\"]}"),
                        Map.of("key", "B", "url", "https://b.example.com/" + unique(""), "weight", 1,
                                "matchRule", "{\"devices\":[\"mobile\"]}"))));
        var resp = postJson("/api/links", json, token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        String code = body(resp).path("data").path("code").asText();
        // The audit's reproduced scenario: legitimate all-mobile traffic.
        seedBucket(code, "B", 100);

        JsonNode data = report(token, code);
        assertEquals("NOT_APPLICABLE", data.path("verdict").asText());
        assertEquals("CONDITIONAL_ROUTING", data.path("reason").asText());
        assertTrue(data.path("pValue").isNull(), "no significance conclusion may ride along");
        assertTrue(data.path("chiSquare").isNull());
        assertEquals(100, data.path("totalClicks").asLong());
        assertEquals(0, rowOf(data, "A").path("observed").asLong());
        assertTrue(rowOf(data, "B").path("expected").isNull(),
                "fit expectations are nulled when the fit does not apply");
    }

    @Test
    void unknownOrChangedConfigurationIsNotApplicable() throws Exception {
        String token = registerForToken();

        // Changed: born an hour ago (backdated link row only), then a real
        // PATCH replaces the whole variant set inside the window — the new
        // rows' install stamp postdates the birth beyond tolerance.
        String changedCode = createVariantLink(token, 1, 1);
        jdbc.update("UPDATE lf_short_link SET created_at = ? WHERE code = ?",
                LocalDateTime.now().minusHours(1), changedCode);
        String patch = om.writeValueAsString(Map.of("variants", List.of(
                Map.of("key", "A", "url", "https://a.example.com/" + unique(""), "weight", 3),
                Map.of("key", "B", "url", "https://b.example.com/" + unique(""), "weight", 1))));
        var patchResp = patchJson("/api/links/" + changedCode, patch, token);
        assertEquals(200, patchResp.getStatusCode().value(), patchResp.getBody());
        seedBucket(changedCode, "A", 75);
        seedBucket(changedCode, "B", 25);
        JsonNode changed = report(token, changedCode);
        assertEquals("NOT_APPLICABLE", changed.path("verdict").asText());
        assertEquals("CONFIG_CHANGED_IN_WINDOW", changed.path("reason").asText());
        assertTrue(changed.path("pValue").isNull());

        // Unknown: a just-created link — its install stamp cannot be told
        // apart from its birth install, so stability is unprovable.
        String freshCode = createVariantLink(token, 1, 1);
        seedBucket(freshCode, "A", 50);
        seedBucket(freshCode, "B", 50);
        JsonNode unknown = report(token, freshCode);
        assertEquals("NOT_APPLICABLE", unknown.path("verdict").asText());
        assertEquals("CONFIG_HISTORY_UNKNOWN", unknown.path("reason").asText());
        assertTrue(unknown.path("pValue").isNull());
    }

    @Test
    void stableWildcardConfigurationKeepsFitDiagnosis() throws Exception {
        String token = registerForToken();

        String evenCode = createVariantLink(token, 1, 1);
        backdateConfig(evenCode, 30);
        seedBucket(evenCode, "A", 50);
        seedBucket(evenCode, "B", 50);
        JsonNode even = report(token, evenCode);
        assertEquals("CONSISTENT", even.path("verdict").asText());
        assertTrue(even.path("reason").isNull());
        assertEquals(0.0, even.path("chiSquare").asDouble(), 1e-9);
        assertEquals(1.0, even.path("pValue").asDouble(), 1e-9);

        // The diagnosis still bites on a provably stable configuration.
        String skewedCode = createVariantLink(token, 1, 1);
        backdateConfig(skewedCode, 30);
        seedBucket(skewedCode, "A", 95);
        seedBucket(skewedCode, "B", 5);
        JsonNode skewed = report(token, skewedCode);
        assertEquals("SKEWED", skewed.path("verdict").asText());
        assertTrue(skewed.path("reason").isNull());
        assertTrue(skewed.path("pValue").asDouble() < 0.05);
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
