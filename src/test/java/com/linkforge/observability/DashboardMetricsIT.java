package com.linkforge.observability;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 (D-Bw345-48): the committed Grafana dashboard may only reference metrics the app
 * actually declares — the dashboard rotting away from the code is the classic failure
 * mode. Every feature that CAN fire in this context is triggered first (redirect,
 * skip, bloom/absent, unlock, API key + quota); the remainder sits in CONTEXT_LIMITED
 * because these contexts intentionally disable the webhook relay, keep phishing lists
 * empty and quotas high — those names are reviewed against the source by the audit.
 * Exported-but-unused metrics are printed, not failed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@ActiveProfiles("test")
class DashboardMetricsIT extends AbstractIntegrationTest {

    private static final Path DASHBOARD =
            Path.of("ops/observability/grafana/dashboards/linkforge-slo.json");
    private static final Pattern LINKFORGE_METRIC = Pattern.compile("linkforge_[a-z0-9_]+");

    /**
     * Counters the observability context cannot fire (see class doc). Each name is
     * still a real counter in the code: grep the metric string in src/main to verify.
     */
    private static final Set<String> CONTEXT_LIMITED = Set.of(
            "linkforge_ratelimit_rejected_total",
            "linkforge_ratelimit_failopen_total",
            "linkforge_outbox_sent_total",
            "linkforge_outbox_failed_total",
            "linkforge_webhook_delivery_total",
            "linkforge_webhook_blocked_total",
            "linkforge_webhook_relay_skipped_total",
            "linkforge_webhook_failed_total",
            "linkforge_phishing_gate_total",
            "linkforge_phishing_continue_total",
            "linkforge_singleflight_shared_total",
            "linkforge_singleflight_timeout_total",
            // a Bloom-negative 404 never reaches the DB, so the absent cache is never
            // written in this context (only Bloom false positives would write it)
            "linkforge_cache_absent_hit_total");

    @DynamicPropertySource
    static void observabilityProperties(DynamicPropertyRegistry registry) {
        // identical to PrometheusScrapeIT so both classes share one context
        registry.add("linkforge.metrics.scrape-token", () -> PrometheusScrapeIT.TOKEN);
        registry.add("linkforge.metrics.backlog-sample-interval-ms", () -> "500");
        registry.add("linkforge.rate-limit.key-prefix", () -> "linkforge:rl:it-prom:");
        registry.add("linkforge.rate-limit.register-max", () -> "1000");
        registry.add("linkforge.rate-limit.create-max", () -> "200");
        registry.add("linkforge.rate-limit.redirect-max", () -> "1000");
    }

    @Autowired
    private com.linkforge.link.support.UrlLocalCache urlLocalCache;

    private String scrape() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + PrometheusScrapeIT.TOKEN);
        ResponseEntity<String> resp = rest.exchange("/actuator/prometheus", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertEquals(200, resp.getStatusCode().value(), "scrape must succeed");
        return resp.getBody();
    }

    @Test
    void dashboardReferencesOnlyDeclaredMetrics() throws Exception {
        assertTrue(Files.exists(DASHBOARD), "the dashboard JSON is part of the deliverable: " + DASHBOARD);
        String json = Files.readString(DASHBOARD);
        assertFalse(json.contains("${DS_"), "no datasource placeholders may remain");

        triggerEveryFeatureThisContextCanFire();

        Set<String> referenced = new HashSet<>();
        Matcher matcher = LINKFORGE_METRIC.matcher(json);
        while (matcher.find()) {
            referenced.add(matcher.group());
        }
        assertFalse(referenced.isEmpty(), "the dashboard must reference linkforge metrics");

        String scrape = scrape();
        assertNotNull(scrape);
        Set<String> missing = new HashSet<>(referenced);
        missing.removeIf(name -> scrape.contains(name) || CONTEXT_LIMITED.contains(name));
        assertTrue(missing.isEmpty(),
                "dashboard references metrics the app never declares: " + missing);

        Set<String> families = new HashSet<>();
        for (String line : scrape.split("\n")) {
            if (line.startsWith("linkforge_")) {
                families.add(line.substring(0, line.indexOf('{') < 0 ? line.length() : line.indexOf('{'))
                        .replaceFirst("_(bucket|count|sum|max|total)$", ""));
            }
        }
        Set<String> unused = new HashSet<>();
        for (String family : families) {
            boolean referencedAsFamily = referenced.stream()
                    .anyMatch(name -> name.replaceFirst("_(bucket|count|sum|max|total)$", "").equals(family)
                            || name.startsWith(family));
            if (!referencedAsFamily) {
                unused.add(family);
            }
        }
        // informational only — a metric nobody looks at is not a defect
        if (!unused.isEmpty()) {
            System.out.println("DASHBOARD-METRICS-IT: exported but not on the dashboard: " + unused);
        }
    }

    private void triggerEveryFeatureThisContextCanFire() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/dash-" + unique(""));
        assertEquals(302, get("/r/" + code, null).getStatusCode().value(), "a counted redirect");
        // prefetch skip
        assertEquals(302, getWithHeader("/r/" + code, null, "Sec-Purpose", "prefetch").getStatusCode().value());
        // L1 miss + L2 round-trip (create() wrote both caches)
        urlLocalCache.invalidate(code);
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());
        // bloom rejection (never-created code)
        assertEquals(404, get("/r/" + unique("nosuch"), null).getStatusCode().value());

        // locked page + unlock results
        var created = postJson("/api/links", om.writeValueAsString(java.util.Map.of(
                "originalUrl", "https://example.com/dash-locked-" + unique(""),
                "accessPassword", "dash-pass-1234")), token);
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        String locked = om.readTree(created.getBody()).path("data").path("code").asText();
        assertEquals(200, get("/r/" + locked, null).getStatusCode().value(), "the unlock page");
        assertEquals(401, postForm("/r/" + locked + "/unlock", "password=wrong", null).getStatusCode().value());
        assertEquals(303, postForm("/r/" + locked + "/unlock", "password=dash-pass-1234", null).getStatusCode().value());

        // API key auth + quota rejection (per-key minute quota of 1)
        var keyResp = postJson("/api/keys", om.writeValueAsString(java.util.Map.of(
                "name", "dash-quota", "quotaPerMinute", 1)), token);
        assertEquals(200, keyResp.getStatusCode().value(), keyResp.getBody());
        String key = om.readTree(keyResp.getBody()).path("data").path("key").asText();
        postJsonWithKey("/api/links", om.writeValueAsString(java.util.Map.of(
                "originalUrl", "https://example.com/dash-key")), key);
        assertEquals(429, postJsonWithKey("/api/links", om.writeValueAsString(java.util.Map.of(
                "originalUrl", "https://example.com/dash-key-2")), key).getStatusCode().value(),
                "the per-key quota must reject the second request");
    }

    private ResponseEntity<String> postForm(String uri, String form, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
        return rest.exchange(uri, HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
    }

    private ResponseEntity<String> postJsonWithKey(String uri, String json, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-API-Key", key);
        return rest.exchange(uri, HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }
}
