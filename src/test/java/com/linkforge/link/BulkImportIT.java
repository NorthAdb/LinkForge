package com.linkforge.link;

import com.fasterxml.jackson.databind.JsonNode;
import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3 bulk CSV import (D-C3-14..25): synchronous multipart import, per-row partial
 * success, file-line row numbers, a dedicated import:user bucket that never touches
 * create:user, and the export round-trip. Row numbers are FILE lines (header = 1,
 * first data row = 2).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-imp1:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=3",
        "linkforge.rate-limit.redirect-max=1000",
        "linkforge.phishing.block-domains=blocked.invalid",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class BulkImportIT extends AbstractIntegrationTest {

    /** 4-16 chars of [A-Za-z0-9_-]: unique enough per test run for a custom code. */
    private static String shortUnique() {
        return unique("").substring(0, 9);
    }

    private ResponseEntity<String> importCsv(String token, String csv) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "import.csv";
            }
        });
        return rest.exchange("/api/links/import", HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
    }

    @Test
    void importThreeRowsCreatesLinks() throws Exception {
        String token = registerForToken();
        String csv = "original_url,title\n"
                + "https://example.com/one," + shortUnique() + "\n"
                + "https://example.com/two," + shortUnique() + "\n"
                + "https://example.com/three," + shortUnique() + "\n";
        ResponseEntity<String> resp = importCsv(token, csv);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        JsonNode data = body(resp).path("data");
        assertEquals(3, data.path("successCount").asInt(), resp.getBody());
        assertEquals(0, data.path("failureCount").asInt(), resp.getBody());
        assertEquals(3, data.path("successes").size(), resp.getBody());
        assertEquals(2, data.path("successes").get(0).path("row").asInt(), "first data row is file line 2");
        for (JsonNode s : data.path("successes")) {
            String code = s.path("code").asText();
            assertFalse(code.isBlank(), resp.getBody());
            assertEquals(200, get("/api/links/" + code, token).getStatusCode().value(),
                    "row must be really inserted: " + code);
            assertEquals(302, get("/r/" + code, null).getStatusCode().value(),
                    "Bloom/absent must know the imported code: " + code);
        }
    }

    @Test
    void partialFailureKeepsGoodRows() throws Exception {
        String token = registerForToken();
        String csv = "original_url,title\n"
                + "https://example.com/ok1,t1\n"
                + "https://example.com/ok2,t2\n"
                + "https://x.blocked.invalid/steal,t3\n";
        ResponseEntity<String> resp = importCsv(token, csv);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        JsonNode data = body(resp).path("data");
        assertEquals(2, data.path("successCount").asInt(), resp.getBody());
        assertEquals(1, data.path("failureCount").asInt(), resp.getBody());
        JsonNode failure = data.path("failures").get(0);
        assertEquals(4, failure.path("row").asInt(), "the 3rd data row sits on file line 4: " + resp.getBody());
        assertFalse(failure.path("reason").asText().isBlank(), resp.getBody());
        assertFalse(failure.path("reason").asText().contains("blocked.invalid"), "no domain echo");
        for (JsonNode s : data.path("successes")) {
            assertEquals(200, get("/api/links/" + s.path("code").asText(), token).getStatusCode().value(),
                    "a failed row must not roll back its neighbours");
        }
    }

    @Test
    void overHundredRowsRejectedWholesale() throws Exception {
        String token = registerForToken();
        StringBuilder csv = new StringBuilder("original_url,custom_code\n");
        String canaryCode = "imp" + shortUnique();
        csv.append("https://example.com/canary,").append(canaryCode).append('\n');
        for (int i = 0; i < 100; i++) {
            csv.append("https://example.com/bulk-").append(shortUnique()).append(",\n");
        }
        ResponseEntity<String> resp = importCsv(token, csv.toString());
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
        assertEquals(404, get("/api/links/" + canaryCode, token).getStatusCode().value(),
                "a 101-row file must leave zero writes, even its first row");
    }

    @Test
    void missingRequiredColumnRejected() throws Exception {
        String token = registerForToken();
        ResponseEntity<String> resp = importCsv(token, "code,title\nabc123,hello\n");
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
        assertTrue(body(resp).path("message").asText().contains("original_url"), resp.getBody());
    }

    @Test
    void importIgnoresCreateUserWindow() throws Exception {
        String token = registerForToken();
        StringBuilder csv = new StringBuilder("original_url\n");
        for (int i = 0; i < 10; i++) {
            csv.append("https://example.com/no-window-").append(shortUnique()).append('\n');
        }
        ResponseEntity<String> resp = importCsv(token, csv.toString());
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        assertEquals(10, body(resp).path("data").path("successCount").asInt(), resp.getBody());
        // Contrast: the plain create API on the same fresh user stops at create-max=3.
        for (int i = 0; i < 3; i++) {
            assertEquals(200, postJson("/api/links",
                    om.writeValueAsString(java.util.Map.of("originalUrl", "https://example.com/c" + i)), token)
                    .getStatusCode().value());
        }
        ResponseEntity<String> fourth = postJson("/api/links",
                om.writeValueAsString(java.util.Map.of("originalUrl", "https://example.com/c3")), token);
        assertEquals(429, fourth.getStatusCode().value(), fourth.getBody());
    }

    @Test
    void importRateLimitIsDedicated() throws Exception {
        String token = registerForToken();
        for (int i = 0; i < 5; i++) {
            ResponseEntity<String> resp = importCsv(token,
                    "original_url\nhttps://example.com/imp-ok-" + shortUnique() + "\n");
            assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        }
        ResponseEntity<String> sixth = importCsv(token,
                "original_url\nhttps://example.com/imp-over-" + shortUnique() + "\n");
        assertEquals(429, sixth.getStatusCode().value(), sixth.getBody());
        assertNotNull(sixth.getHeaders().getFirst("Retry-After"), "429 must carry Retry-After");
        // Per-user bucket: a fresh user still has its own import window.
        String other = registerForToken();
        assertEquals(200, importCsv(other,
                "original_url\nhttps://example.com/imp-other-" + shortUnique() + "\n").getStatusCode().value());
    }

    @Test
    void exportedCsvReimports() throws Exception {
        String token = registerForToken();
        String codeA = createLink(token, "https://example.com/reimport-a");
        String codeB = createLink(token, "https://example.com/reimport-b");
        ResponseEntity<String> export = get("/api/links/export", token);
        assertEquals(200, export.getStatusCode().value(), export.getBody());
        String csv = export.getBody();
        assertTrue(csv.startsWith("\uFEFF"), "export carries a BOM");
        ResponseEntity<String> resp = importCsv(token, csv);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        JsonNode data = body(resp).path("data");
        assertEquals(2, data.path("successCount").asInt(), resp.getBody());
        assertEquals(0, data.path("failureCount").asInt(), resp.getBody());
        // The code column is ignored on import: the round-trip mints NEW codes.
        for (JsonNode s : data.path("successes")) {
            assertNotEquals(codeA, s.path("code").asText());
            assertNotEquals(codeB, s.path("code").asText());
        }
    }
}
