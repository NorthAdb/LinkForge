package com.linkforge.stats;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.stats.entity.ClickLog;
import com.linkforge.stats.mapper.ClickLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 3b: raw click export. Rows are seeded straight into lf_click_log (the
 * table the export reads), the cap is shrunk to 5 by property so truncation
 * is testable without seeding a hundred thousand rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-csvexport:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.redirect-max=1000",
        "linkforge.clicks-export-max-rows=5",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class ClicksExportIT extends AbstractIntegrationTest {

    @Autowired
    private ClickLogMapper clickLogMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> seededIds = new ArrayList<>();

    @AfterEach
    void cleanSeededClicks() {
        if (!seededIds.isEmpty()) {
            clickLogMapper.delete(new LambdaQueryWrapper<ClickLog>().in(ClickLog::getId, seededIds));
            seededIds.clear();
        }
    }

    private long seedClick(String code, LocalDateTime clickedAt, String userAgent) {
        Long linkId = jdbc.queryForObject("SELECT id FROM lf_short_link WHERE code = ?", Long.class, code);
        Long tenantId = jdbc.queryForObject("SELECT tenant_id FROM lf_short_link WHERE code = ?", Long.class, code);
        ClickLog row = new ClickLog();
        row.setTenantId(tenantId);
        row.setLinkId(linkId);
        row.setCode(code);
        row.setIp("203.0.113.9");
        row.setUserAgent(userAgent);
        row.setReferer("https://referrer.example/page");
        row.setClickedAt(clickedAt);
        clickLogMapper.insert(row);
        seededIds.add(row.getId());
        return row.getId();
    }

    private static long dataRows(String csv) {
        return csv.lines().filter(line -> !line.startsWith("\uFEFFclicked_at") && !line.startsWith("#")).count();
    }

    @Test
    void exportMatchesTheClicksDetailRows() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/export-" + unique(""));
        seedClick(code, LocalDateTime.now(), "Agent One");
        seedClick(code, LocalDateTime.now(), "Bad, \"Agent\"");
        seedClick(code, LocalDateTime.now(), "Agent Three");

        var detail = get("/api/links/" + code + "/clicks?size=100", token);
        assertEquals(3, body(detail).path("data").path("total").asLong());

        ResponseEntity<String> export = get("/api/links/" + code + "/clicks/export", token);
        assertEquals(200, export.getStatusCode().value(), export.getBody());
        String csv = export.getBody();
        assertTrue(csv.startsWith("\uFEFFclicked_at,ip,user_agent,referer,variant"), csv);
        assertEquals(3, dataRows(csv));
        // RFC4180 escaping: the comma+quote UA is quoted with doubled quotes
        assertTrue(csv.contains("\"Bad, \"\"Agent\"\"\""), csv);
        assertNull(export.getHeaders().getFirst("X-Export-Truncated"));
    }

    @Test
    void exportStopsAtTheCapAndFlagsIt() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/export-cap-" + unique(""));
        for (int i = 0; i < 7; i++) {
            seedClick(code, LocalDateTime.now(), "Agent " + i);
        }
        ResponseEntity<String> export = get("/api/links/" + code + "/clicks/export", token);
        assertEquals(200, export.getStatusCode().value(), export.getBody());
        assertEquals("true", export.getHeaders().getFirst("X-Export-Truncated"));
        assertEquals(5, dataRows(export.getBody()));
        assertTrue(export.getBody().contains("# truncated at 5 rows"), export.getBody());
    }

    @Test
    void exportWindowFollowsTheDaysConvention() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/export-win-" + unique(""));
        seedClick(code, LocalDateTime.now(), "Fresh Agent");
        seedClick(code, LocalDateTime.now().minusDays(40), "Stale Agent");

        ResponseEntity<String> month = get("/api/links/" + code + "/clicks/export", token);
        assertEquals(1, dataRows(month.getBody()), month.getBody());
        assertFalse(month.getBody().contains("Stale Agent"));

        ResponseEntity<String> quarter = get("/api/links/" + code + "/clicks/export?days=90", token);
        assertEquals(2, dataRows(quarter.getBody()), quarter.getBody());
    }

    @Test
    void exportIsOwnerOnly() throws Exception {
        String owner = registerForToken();
        String code = createLink(owner, "https://example.com/export-own-" + unique(""));
        seedClick(code, LocalDateTime.now(), "Agent");
        String stranger = registerForToken();
        ResponseEntity<String> export = get("/api/links/" + code + "/clicks/export", stranger);
        assertEquals(404, export.getStatusCode().value(), export.getBody());
    }
}
