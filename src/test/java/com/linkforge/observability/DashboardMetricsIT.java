package com.linkforge.observability;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
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
 * B1 (D-Bw345-48): the committed Grafana dashboard may only reference metrics that a
 * real scrape actually exports — the dashboard rotting away from the code is the
 * classic failure mode. The reverse list (exported but unused) is printed, not failed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@ActiveProfiles("test")
class DashboardMetricsIT extends AbstractIntegrationTest {

    private static final Path DASHBOARD =
            Path.of("ops/observability/grafana/dashboards/linkforge-slo.json");
    private static final Pattern LINKFORGE_METRIC = Pattern.compile("linkforge_[a-z0-9_]+");

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

    private String scrape() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + PrometheusScrapeIT.TOKEN);
        ResponseEntity<String> resp = rest.exchange("/actuator/prometheus", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertEquals(200, resp.getStatusCode().value(), "scrape must succeed");
        return resp.getBody();
    }

    @Test
    void dashboardReferencesOnlyExportedMetrics() throws Exception {
        assertTrue(Files.exists(DASHBOARD), "the dashboard JSON is part of the deliverable: " + DASHBOARD);
        String json = Files.readString(DASHBOARD);
        assertFalse(json.contains("${DS_"), "no datasource placeholders may remain");

        Set<String> referenced = new HashSet<>();
        Matcher matcher = LINKFORGE_METRIC.matcher(json);
        while (matcher.find()) {
            referenced.add(matcher.group());
        }
        assertFalse(referenced.isEmpty(), "the dashboard must reference linkforge metrics");

        String scrape = scrape();
        assertNotNull(scrape);
        Set<String> missing = new HashSet<>(referenced);
        missing.removeIf(name -> scrape.contains(name));
        assertTrue(missing.isEmpty(), "dashboard references metrics the scrape never exports: " + missing);

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
}
