package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-p3:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=200",
        "linkforge.rate-limit.redirect-max=1000"
})
@ActiveProfiles("test")
class CsvExportIT extends AbstractIntegrationTest {

    @Test
    void exportContainsOnlyOwnLinksAndEscapesQuoting() throws Exception {
        String tokenA = registerForToken();
        String tokenB = registerForToken();
        String codeA1 = createLink(tokenA, "https://example.com/csv-1");
        String codeA2 = (String) body(postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://example.com/csv-2",
                "title", "a,b\"c")), tokenA)).path("data").path("code").asText();
        String codeB = createLink(tokenB, "https://example.com/csv-other");

        var resp = get("/api/links/export", tokenA);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        assertTrue(resp.getHeaders().getFirst("Content-Disposition").contains("links.csv"));
        String csv = resp.getBody();
        assertTrue(csv.startsWith("\uFEFFcode,original_url,title,status,expire_at,created_at"), csv);
        assertTrue(csv.contains(codeA1), "own first link must be exported");
        assertTrue(csv.contains(codeA2), "own second link must be exported");
        assertFalse(csv.contains(codeB), "other user's link must not leak into export");
        assertTrue(csv.contains("\"a,b\"\"c\""), "comma/quote title must be CSV-escaped: " + csv);
    }

    @Test
    void exportRequiresAuth() {
        assertEquals(401, get("/api/links/export", null).getStatusCode().value());
    }

    /** Splits one CSV line into parsed cell values (RFC4180 quoting undone). */
    private static List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                cells.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString());
        return cells;
    }

    @Test
    void userControlledTitlesAreSafe() throws Exception {
        String token = registerForToken();
        // M8: an owner-controlled title starting with '=' must reach the CSV
        // as inert spreadsheet text; a quoting-heavy title must round-trip
        // through RFC escaping unchanged (and stay non-formula).
        String formulaCode = (String) body(postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://example.com/csv-formula",
                "title", "=2+2")), token)).path("data").path("code").asText();
        String quotedCode = (String) body(postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://example.com/csv-quoted",
                "title", "a,b\"c")), token)).path("data").path("code").asText();

        String csv = get("/api/links/export", token).getBody();
        // Columns: code, original_url, title, status, expire_at, created_at.
        List<String> formulaCells = parseCsvLine(csv.lines()
                .filter(line -> line.contains(formulaCode)).findFirst().orElseThrow());
        assertEquals("'=2+2", formulaCells.get(2),
                "formula title must be neutralized: " + csv);
        List<String> quotedCells = parseCsvLine(csv.lines()
                .filter(line -> line.contains(quotedCode)).findFirst().orElseThrow());
        assertEquals("a,b\"c", quotedCells.get(2),
                "comma/quote title must round-trip through RFC escaping: " + csv);
    }
}
