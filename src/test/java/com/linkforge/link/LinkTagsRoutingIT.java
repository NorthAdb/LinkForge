package com.linkforge.link;

import com.fasterxml.jackson.databind.JsonNode;
import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 3a: link tags + keyword search on the list/export surface, and dynamic
 * routing (device / language / time-window match rules narrowing the A/B draw).
 * Routing draws are made deterministic by giving each variant a rule only one
 * candidate set can satisfy — except the fallback test, which asserts the
 * Location always lands inside the full variant set.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-3a:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=1000",
        "linkforge.rate-limit.redirect-max=1000",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class LinkTagsRoutingIT extends AbstractIntegrationTest {

    private static final String IPHONE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1";
    private static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private String createWith(String token, Map<String, Object> fields) throws Exception {
        ResponseEntity<String> resp = postJson("/api/links", om.writeValueAsString(fields), token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        return body(resp).path("data").path("code").asText();
    }

    private Set<String> listCodes(String token, String query) throws Exception {
        ResponseEntity<String> resp = get("/api/links?size=100" + query, token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        Set<String> codes = new HashSet<>();
        for (JsonNode record : body(resp).path("data").path("records")) {
            codes.add(record.path("code").asText());
        }
        return codes;
    }

    private List<String> detailTags(String token, String code) throws Exception {
        JsonNode tags = body(get("/api/links/" + code, token)).path("data").path("tags");
        assertTrue(tags.isArray(), "detail must echo tags as an array");
        List<String> values = new java.util.ArrayList<>();
        tags.forEach(tag -> values.add(tag.asText()));
        return values;
    }

    private ResponseEntity<String> getWithHeaders(String uri, Map<String, String> headers) {
        HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::set);
        return rest.exchange(uri, HttpMethod.GET, new HttpEntity<>(httpHeaders), String.class);
    }

    // ---------- tags & search ----------

    @Test
    void createNormalizesTagsAndEchoesThem() throws Exception {
        String token = registerForToken();
        String code = createWith(token, Map.of(
                "originalUrl", "https://tags.example.com/" + unique(""),
                "tags", List.of(" Dev ", "PROD", "dev", "")));

        assertEquals(List.of("dev", "prod"), detailTags(token, code),
                "stored tags are trimmed, lowercased, deduped in first-seen order");
        JsonNode records = body(get("/api/links", token)).path("data").path("records");
        assertEquals("dev", records.get(0).path("tags").get(0).asText(),
                "list rows carry the same tags array");
    }

    @Test
    void createRejectsAnInvalidTag() throws Exception {
        String token = registerForToken();
        ResponseEntity<String> resp = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://tags.example.com/" + unique(""),
                "tags", List.of("bad tag!"))), token);
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
    }

    @Test
    void keywordMatchesCodeTitleAndUrl() throws Exception {
        String token = registerForToken();
        String codeMarker = "kw" + unique("").substring(0, 10);
        String byCode = createWith(token, Map.of(
                "originalUrl", "https://kw.example.com/a-" + unique(""), "customCode", codeMarker));
        String titleMarker = unique("title");
        String byTitle = createWith(token, Map.of(
                "originalUrl", "https://kw.example.com/b-" + unique(""), "title", "Report " + titleMarker));
        String urlMarker = unique("path");
        String byUrl = createWith(token, Map.of(
                "originalUrl", "https://kw.example.com/" + urlMarker));

        assertEquals(Set.of(byCode), listCodes(token, "&keyword=" + codeMarker.substring(0, 9)));
        assertEquals(Set.of(byTitle), listCodes(token, "&keyword=" + titleMarker));
        assertEquals(Set.of(byUrl), listCodes(token, "&keyword=" + urlMarker));
    }

    @Test
    void keywordLongerThan64IsA400() throws Exception {
        String token = registerForToken();
        ResponseEntity<String> resp = get("/api/links?keyword=" + "x".repeat(65), token);
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
    }

    @Test
    void tagFilterMatchesWholeWordsOnly() throws Exception {
        String token = registerForToken();
        String devops = createWith(token, Map.of(
                "originalUrl", "https://tag.example.com/" + unique(""), "tags", List.of("devops")));
        String dev = createWith(token, Map.of(
                "originalUrl", "https://tag.example.com/" + unique(""), "tags", List.of("dev")));

        assertEquals(Set.of(dev), listCodes(token, "&tag=dev"),
                "tag=dev must not swallow the devops link");
        assertEquals(Set.of(devops), listCodes(token, "&tag=devops"));
        assertEquals(Set.of(dev), listCodes(token, "&tag=DEV"), "the filter value normalizes case");
        assertEquals(Set.of(), listCodes(token, "&tag=ops"), "a substring of a tag matches nothing");
    }

    @Test
    void keywordAndTagCombine() throws Exception {
        String token = registerForToken();
        String marker = unique("combo");
        String blue = createWith(token, Map.of(
                "originalUrl", "https://combo.example.com/" + unique(""),
                "title", "Doc " + marker, "tags", List.of("blue")));
        createWith(token, Map.of(
                "originalUrl", "https://combo.example.com/" + unique(""),
                "title", "Doc " + marker, "tags", List.of("red")));

        assertEquals(Set.of(blue), listCodes(token, "&keyword=" + marker + "&tag=blue"));
    }

    @Test
    void filtersNeverLeakAcrossTenants() throws Exception {
        String tokenA = registerForToken();
        String tokenB = registerForToken();
        String marker = unique("private");
        createWith(tokenA, Map.of(
                "originalUrl", "https://leak.example.com/" + unique(""),
                "title", "Secret " + marker, "tags", List.of("hidden")));

        assertEquals(Set.of(), listCodes(tokenB, "&keyword=" + marker));
        assertEquals(Set.of(), listCodes(tokenB, "&tag=hidden"));
    }

    @Test
    void updateTagsFollowTheThreeStateShape() throws Exception {
        String token = registerForToken();
        String code = createWith(token, Map.of(
                "originalUrl", "https://tags.example.com/" + unique(""), "tags", List.of("one")));

        ResponseEntity<String> titleOnly = patchJson("/api/links/" + code,
                om.writeValueAsString(Map.of("title", "renamed")), token);
        assertEquals(200, titleOnly.getStatusCode().value(), titleOnly.getBody());
        assertEquals(List.of("one"), detailTags(token, code), "an absent tags field leaves tags alone");

        ResponseEntity<String> replaced = patchJson("/api/links/" + code,
                om.writeValueAsString(Map.of("tags", List.of("Two", "three"))), token);
        assertEquals(200, replaced.getStatusCode().value(), replaced.getBody());
        assertEquals(List.of("two", "three"), detailTags(token, code));

        ResponseEntity<String> cleared = patchJson("/api/links/" + code,
                om.writeValueAsString(Map.of("tags", List.of())), token);
        assertEquals(200, cleared.getStatusCode().value(), cleared.getBody());
        assertEquals(List.of(), detailTags(token, code), "an empty array clears every tag");
    }

    @Test
    void exportHonorsTheTagFilter() throws Exception {
        String token = registerForToken();
        String tagged = createWith(token, Map.of(
                "originalUrl", "https://exp.example.com/" + unique(""), "tags", List.of("exp")));
        String untagged = createLink(token, "https://exp.example.com/" + unique(""));

        String csv = get("/api/links/export?tag=exp", token).getBody();
        assertTrue(csv.contains(tagged), "the tagged link is exported: " + csv);
        assertFalse(csv.contains(untagged), "the untagged link is filtered out: " + csv);
    }

    // ---------- dynamic routing ----------

    private String createRouted(String token, List<Map<String, Object>> variants) throws Exception {
        return createWith(token, Map.of(
                "originalUrl", "https://route-main.example.com/" + unique(""),
                "variants", variants));
    }

    private static Map<String, Object> variant(String key, String url, String matchRule) {
        return matchRule == null
                ? Map.of("key", key, "url", url, "weight", 50)
                : Map.of("key", key, "url", url, "weight", 50, "matchRule", matchRule);
    }

    @Test
    void deviceRuleRoutesMobileAndDesktopDeterministically() throws Exception {
        String token = registerForToken();
        String urlMobile = "https://route-m.example.com/" + unique("");
        String urlDesktop = "https://route-d.example.com/" + unique("");
        String code = createRouted(token, List.of(
                variant("M", urlMobile, "{\"devices\":[\"mobile\"]}"),
                variant("D", urlDesktop, "{\"devices\":[\"desktop\"]}")));

        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> mobile = getWithHeaders("/r/" + code, Map.of("User-Agent", IPHONE_UA));
            assertEquals(302, mobile.getStatusCode().value(), mobile.getBody());
            assertEquals(urlMobile, String.valueOf(mobile.getHeaders().getLocation()));
            ResponseEntity<String> desktop = getWithHeaders("/r/" + code, Map.of("User-Agent", DESKTOP_UA));
            assertEquals(urlDesktop, String.valueOf(desktop.getHeaders().getLocation()));
        }
    }

    @Test
    void languageRuleUsesTheAcceptLanguagePrimaryTag() throws Exception {
        String token = registerForToken();
        String urlZh = "https://route-zh.example.com/" + unique("");
        String urlEn = "https://route-en.example.com/" + unique("");
        String code = createRouted(token, List.of(
                variant("ZH", urlZh, "{\"languages\":[\"zh\"]}"),
                variant("EN", urlEn, "{\"languages\":[\"en\"]}")));

        ResponseEntity<String> zh = getWithHeaders("/r/" + code,
                Map.of("User-Agent", DESKTOP_UA, "Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8"));
        assertEquals(urlZh, String.valueOf(zh.getHeaders().getLocation()));
        ResponseEntity<String> en = getWithHeaders("/r/" + code,
                Map.of("User-Agent", DESKTOP_UA, "Accept-Language", "en-US,en;q=0.9"));
        assertEquals(urlEn, String.valueOf(en.getHeaders().getLocation()));
    }

    @Test
    void noMatchingRuleFallsBackToTheFullVariantSet() throws Exception {
        String token = registerForToken();
        String urlMobile = "https://route-m.example.com/" + unique("");
        String urlDesktop = "https://route-d.example.com/" + unique("");
        String code = createRouted(token, List.of(
                variant("M", urlMobile, "{\"devices\":[\"mobile\"]}"),
                variant("D", urlDesktop, "{\"devices\":[\"desktop\"]}")));

        // curl's UA classifies as device=bot: neither rule matches, so the draw
        // must fall back to the whole set instead of erroring or 404ing.
        for (int i = 0; i < 10; i++) {
            ResponseEntity<String> resp = getWithHeaders("/r/" + code, Map.of("User-Agent", "curl/8.5.0"));
            assertEquals(302, resp.getStatusCode().value(), resp.getBody());
            String location = String.valueOf(resp.getHeaders().getLocation());
            assertTrue(urlMobile.equals(location) || urlDesktop.equals(location),
                    "fallback draws stay inside the variant set, got " + location);
        }
    }

    @Test
    void wildcardVariantStaysCandidateWhenRulesExcludeTheRest() throws Exception {
        String token = registerForToken();
        String urlMobile = "https://route-m.example.com/" + unique("");
        String urlWild = "https://route-w.example.com/" + unique("");
        String code = createRouted(token, List.of(
                variant("M", urlMobile, "{\"devices\":[\"mobile\"]}"),
                variant("W", urlWild, null)));

        // Desktop: the mobile rule excludes M, the wildcard W always qualifies.
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> resp = getWithHeaders("/r/" + code, Map.of("User-Agent", DESKTOP_UA));
            assertEquals(urlWild, String.valueOf(resp.getHeaders().getLocation()));
        }
    }

    @Test
    void timeWindowRuleNarrowsByTheClock() throws Exception {
        String token = registerForToken();
        String urlAllDay = "https://route-all.example.com/" + unique("");
        String urlOneMinute = "https://route-min.example.com/" + unique("");
        // Two windows tile the whole UTC day with no gap (12:00->00:00 wraps).
        String allDay = "{\"timeWindows\":[{\"from\":\"00:00\",\"to\":\"12:00\",\"tz\":\"UTC\"},"
                + "{\"from\":\"12:00\",\"to\":\"00:00\",\"tz\":\"UTC\"}]}";
        // A one-minute window pinned to a weekday that is NOT today (UTC): the
        // clock gate excludes MIN at every instant of today, deterministically.
        int notToday = java.time.LocalDate.now(java.time.ZoneOffset.UTC).getDayOfWeek().getValue() % 7 + 1;
        String oneMinute = "{\"timeWindows\":[{\"from\":\"03:00\",\"to\":\"03:01\",\"tz\":\"UTC\","
                + "\"weekdays\":[" + notToday + "]}]}";
        String code = createRouted(token, List.of(
                variant("ALL", urlAllDay, allDay),
                variant("MIN", urlOneMinute, oneMinute)));

        // MIN is clock-excluded all day today, so ALL is the only candidate.
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> resp = getWithHeaders("/r/" + code, Map.of("User-Agent", DESKTOP_UA));
            assertEquals(302, resp.getStatusCode().value(), resp.getBody());
            assertEquals(urlAllDay, String.valueOf(resp.getHeaders().getLocation()));
        }
    }

    @Test
    void invalidMatchRuleIsA400() throws Exception {
        String token = registerForToken();
        ResponseEntity<String> unknownDevice = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://route-main.example.com/" + unique(""),
                "variants", List.of(variant("X", "https://route-x.example.com/" + unique(""),
                        "{\"devices\":[\"hoverboard\"]}")))), token);
        assertEquals(400, unknownDevice.getStatusCode().value(), unknownDevice.getBody());

        ResponseEntity<String> malformed = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://route-main.example.com/" + unique(""),
                "variants", List.of(variant("X", "https://route-x.example.com/" + unique(""),
                        "{oops")))), token);
        assertEquals(400, malformed.getStatusCode().value(), malformed.getBody());
    }

    @Test
    void matchRuleIsEchoedInTheDetailView() throws Exception {
        String token = registerForToken();
        String code = createRouted(token, List.of(
                variant("M", "https://route-m.example.com/" + unique(""), "{\"devices\":[\"mobile\"]}")));

        JsonNode variants = body(get("/api/links/" + code, token)).path("data").path("variants");
        assertEquals(1, variants.size());
        assertTrue(variants.get(0).path("matchRule").asText().contains("mobile"),
                "the detail view echoes the stored rule: " + variants);
    }
}
