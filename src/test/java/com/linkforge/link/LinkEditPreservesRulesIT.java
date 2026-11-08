package com.linkforge.link;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.linkforge.AbstractIntegrationTest;
import com.linkforge.link.entity.LinkVariant;
import com.linkforge.link.mapper.LinkVariantMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M6 (audit batches 1-3): editing a link must never silently destroy the
 * dynamic-routing rules (matchRule) it carries. The backend contract under
 * test: a PATCH without a "variants" field leaves the whole variant set —
 * rules included — untouched; a submitted set replaces it wholesale, with an
 * explicit matchRule:null clearing exactly that variant's rule. (The console
 * half of M6 — omit-when-unchanged — is covered by
 * scripts/ui-contract-test.mjs.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-m6:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=1000",
        "linkforge.rate-limit.redirect-max=1000",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class LinkEditPreservesRulesIT extends AbstractIntegrationTest {

    private static final String RULE_A = "{\"devices\":[\"mobile\"],\"os\":[\"ios\",\"android\"]}";
    private static final String RULE_B = "{\"languages\":[\"zh\"],\"timeWindows\":[{\"from\":\"09:00\","
            + "\"to\":\"18:00\",\"tz\":\"Asia/Shanghai\",\"weekdays\":[1,2,3,4,5]}]}";

    @Autowired
    private LinkVariantMapper variantMapper;

    private record Created(String code, long linkId, String urlA, String urlB) {
    }

    private Created createRuledLink(String token) throws Exception {
        String urlA = "https://m6-a.example.com/" + unique("");
        String urlB = "https://m6-b.example.com/" + unique("");
        ResponseEntity<String> resp = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://m6-main.example.com/" + unique(""),
                "title", "before",
                "variants", List.of(
                        Map.of("key", "a", "url", urlA, "weight", 60, "matchRule", RULE_A),
                        Map.of("key", "b", "url", urlB, "weight", 40, "matchRule", RULE_B)))), token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        JsonNode data = body(resp).path("data");
        return new Created(data.path("code").asText(), data.path("id").asLong(), urlA, urlB);
    }

    private JsonNode detailVariants(String token, String code) throws Exception {
        ResponseEntity<String> resp = get("/api/links/" + code, token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        return body(resp).path("data").path("variants");
    }

    /** Structural (key-order-insensitive) comparison of two matchRule JSON strings. */
    private void assertRuleEquals(String expectedRule, JsonNode variantNode, String what) throws Exception {
        JsonNode ruleNode = variantNode.path("matchRule");
        assertTrue(ruleNode.isTextual(), what + ": matchRule must be present as a JSON string: " + variantNode);
        assertEquals(om.readTree(expectedRule), om.readTree(ruleNode.asText()),
                what + ": matchRule must be deep-equal to the original");
    }

    private void assertDbRuleEquals(String expectedRule, long linkId, String variantKey) throws Exception {
        LinkVariant row = variantMapper.selectOne(new LambdaQueryWrapper<LinkVariant>()
                .eq(LinkVariant::getLinkId, linkId)
                .eq(LinkVariant::getVariantKey, variantKey));
        assertTrue(row != null && row.getMatchRule() != null,
                "DB must still hold a match_rule for variant " + variantKey);
        assertEquals(om.readTree(expectedRule), om.readTree(row.getMatchRule()),
                "DB match_rule for variant " + variantKey + " must be deep-equal to the original");
    }

    @Test
    void titleOnlyPreservesMatchRules() throws Exception {
        String token = registerForToken();
        Created link = createRuledLink(token);

        // Exactly what the fixed console sends for a title-only edit: no
        // "variants" key at all.
        ResponseEntity<String> patch = patchJson("/api/links/" + link.code(),
                om.writeValueAsString(Map.of("title", "renamed")), token);
        assertEquals(200, patch.getStatusCode().value(), patch.getBody());

        JsonNode detail = body(get("/api/links/" + link.code(), token)).path("data");
        assertEquals("renamed", detail.path("title").asText());
        JsonNode variants = detail.path("variants");
        assertEquals(2, variants.size(), "variant count must not change: " + variants);
        assertEquals("a", variants.get(0).path("key").asText());
        assertEquals(link.urlA(), variants.get(0).path("url").asText());
        assertEquals(60, variants.get(0).path("weight").asInt());
        assertRuleEquals(RULE_A, variants.get(0), "variant a");
        assertEquals("b", variants.get(1).path("key").asText());
        assertEquals(link.urlB(), variants.get(1).path("url").asText());
        assertEquals(40, variants.get(1).path("weight").asInt());
        assertRuleEquals(RULE_B, variants.get(1), "variant b");
        assertDbRuleEquals(RULE_A, link.linkId(), "a");
        assertDbRuleEquals(RULE_B, link.linkId(), "b");
    }

    @Test
    void editVariantPreservesOtherRules() throws Exception {
        String token = registerForToken();
        Created link = createRuledLink(token);

        // Change ONLY variant a's weight; both rules are resubmitted verbatim,
        // as the console's collect path now does.
        ResponseEntity<String> patch = patchJson("/api/links/" + link.code(), om.writeValueAsString(Map.of(
                "variants", List.of(
                        Map.of("key", "a", "url", link.urlA(), "weight", 70, "matchRule", RULE_A),
                        Map.of("key", "b", "url", link.urlB(), "weight", 40, "matchRule", RULE_B)))), token);
        assertEquals(200, patch.getStatusCode().value(), patch.getBody());

        JsonNode variants = detailVariants(token, link.code());
        assertEquals(2, variants.size());
        assertEquals(70, variants.get(0).path("weight").asInt(), "the weight edit must be applied");
        assertRuleEquals(RULE_A, variants.get(0), "edited variant a keeps its rule");
        assertEquals(40, variants.get(1).path("weight").asInt());
        assertRuleEquals(RULE_B, variants.get(1), "untouched variant b keeps its rule");
    }

    @Test
    void explicitRuleClearIsIntentional() throws Exception {
        String token = registerForToken();
        Created link = createRuledLink(token);

        // Explicitly clear ONLY variant a's rule: the JSON carries
        // "matchRule":null for a (a Map cannot hold it, so build raw JSON).
        String json = "{\"variants\":["
                + "{\"key\":\"a\",\"url\":\"" + link.urlA() + "\",\"weight\":60,\"matchRule\":null},"
                + "{\"key\":\"b\",\"url\":\"" + link.urlB() + "\",\"weight\":40,\"matchRule\":"
                + om.writeValueAsString(RULE_B) + "}]}";
        ResponseEntity<String> patch = patchJson("/api/links/" + link.code(), json, token);
        assertEquals(200, patch.getStatusCode().value(), patch.getBody());

        JsonNode variants = detailVariants(token, link.code());
        assertEquals(2, variants.size());
        assertTrue(variants.get(0).path("matchRule").isNull(),
                "an explicit null must clear exactly that rule: " + variants);
        assertRuleEquals(RULE_B, variants.get(1), "variant b rule must survive a sibling clear");
        assertDbRuleEquals(RULE_B, link.linkId(), "b");
    }

    @Test
    void invalidRulePatchFailsWithoutPartialWrite() throws Exception {
        String token = registerForToken();
        Created link = createRuledLink(token);

        // One valid entry + one malformed rule: the whole PATCH must 400 and
        // leave the stored set exactly as it was.
        ResponseEntity<String> patch = patchJson("/api/links/" + link.code(), om.writeValueAsString(Map.of(
                "variants", List.of(
                        Map.of("key", "a", "url", link.urlA(), "weight", 99, "matchRule", RULE_A),
                        Map.of("key", "b", "url", link.urlB(), "weight", 40, "matchRule", "{oops")))), token);
        assertEquals(400, patch.getStatusCode().value(), patch.getBody());

        JsonNode variants = detailVariants(token, link.code());
        assertEquals(2, variants.size());
        assertEquals(60, variants.get(0).path("weight").asInt(),
                "a rejected PATCH must not partially apply: " + variants);
        assertRuleEquals(RULE_A, variants.get(0), "variant a after rejected patch");
        assertRuleEquals(RULE_B, variants.get(1), "variant b after rejected patch");
    }
}
