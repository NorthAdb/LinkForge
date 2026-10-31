package com.linkforge.stats;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.stats.mq.ClickEvent;
import com.linkforge.stats.service.ClickStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C2 wave D (D-C2-21/25/26): os/browser breakdown dimensions. New clicks land in
 * os/browser buckets through the SAME idempotent consume; unknown dims (geo is
 * explicitly not built) stay a 400; device/referer_host regression untouched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-dimos:",
        "linkforge.rate-limit.register-max=1000",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class DimOsBrowserIT extends AbstractIntegrationTest {

    private static final String EDGE_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36 Edg/120.0.2210.61";

    @Autowired
    private ClickStatsService clickStatsService;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Test
    void newClicksLandInOsAndBrowserBuckets() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/dimos-" + System.currentTimeMillis());

        // dim rows key on link_id: consume must use the REAL id, not a synthetic one
        Long linkId = jdbc.queryForObject("SELECT id FROM lf_short_link WHERE code = ?", Long.class, code);
        ClickEvent event = new ClickEvent("dim-os-" + UUID.randomUUID(), tenantIdOf(token), linkId, code, 1L,
                "203.0.113.9", EDGE_UA, "https://news.example.com/", LocalDateTime.now(), null);
        assertTrue(clickStatsService.consume(event), "first consume must win");

        ResponseEntity<String> os = get("/api/links/" + code + "/stats/breakdown?dim=os", token);
        assertEquals(200, os.getStatusCode().value(), "dim=os must be a valid breakdown: " + os.getBody());
        assertTrue(os.getBody().contains("windows"), "the Edge click must bucket as windows: " + os.getBody());

        ResponseEntity<String> browser = get("/api/links/" + code + "/stats/breakdown?dim=browser", token);
        assertEquals(200, browser.getStatusCode().value(), "dim=browser must be valid: " + browser.getBody());
        assertTrue(browser.getBody().contains("edge"), "D-C2-24: Edge must not count as chrome: "
                + browser.getBody());

        ResponseEntity<String> device = get("/api/links/" + code + "/stats/breakdown?dim=device", token);
        assertEquals(200, device.getStatusCode().value(), device.getBody());
        assertTrue(device.getBody().contains("desktop"), "device regression: " + device.getBody());
    }

    @Test
    void unknownDimensionsStayA400() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/dimgeo-" + System.currentTimeMillis());

        assertEquals(400, get("/api/links/" + code + "/stats/breakdown?dim=geo", token).getStatusCode().value(),
                "Geo is explicitly not built (D-C2-21)");
        assertEquals(400, get("/api/links/" + code + "/stats/breakdown", token).getStatusCode().value());
        assertEquals(400, get("/api/links/" + code + "/stats/breakdown?dim=language", token)
                .getStatusCode().value());
    }

    private Long tenantIdOf(String token) {
        try {
            String[] parts = token.split("\\.");
            byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
            return om.readTree(payload).path("tenantId").asLong();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot decode tenant from the test jwt", ex);
        }
    }
}
