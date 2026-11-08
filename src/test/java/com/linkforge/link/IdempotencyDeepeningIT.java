package com.linkforge.link;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 3b idempotency deepening: request fingerprints (same key + different
 * body = 409 IDEMPOTENCY_KEY_REUSED), replays that burn no rate-limit quota,
 * and Idempotency-Key on CSV import. The create bucket is 3/min and the
 * import bucket 2/min, per user, so quota arithmetic is assertable; every
 * test registers a fresh user and gets fresh buckets.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-idem2:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=3",
        "linkforge.rate-limit.import-max=2",
        "linkforge.rate-limit.redirect-max=1000",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class IdempotencyDeepeningIT extends AbstractIntegrationTest {

    private ResponseEntity<String> importCsv(String token, String csv, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(token);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public String getFilename() {
                return "import.csv";
            }
        });
        return rest.exchange("/api/links/import", HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
    }

    private long linkCount(String token) throws Exception {
        var list = get("/api/links?page=1&size=100", token);
        return body(list).path("data").path("total").asLong();
    }

    @Test
    void sameKeySameBodyStillReplays() throws Exception {
        String token = registerForToken();
        String json = om.writeValueAsString(Map.of("originalUrl", "https://example.com/i2-" + unique("")));
        var first = postJsonWithHeader("/api/links", json, token, "Idempotency-Key", "k-same");
        assertEquals(200, first.getStatusCode().value(), first.getBody());
        var replay = postJsonWithHeader("/api/links", json, token, "Idempotency-Key", "k-same");
        assertEquals(200, replay.getStatusCode().value(), replay.getBody());
        assertEquals(body(first).path("data").path("code").asText(),
                body(replay).path("data").path("code").asText());
        assertEquals(1, linkCount(token));
    }

    @Test
    void sameKeyDifferentBodyIsA409() throws Exception {
        String token = registerForToken();
        String first = om.writeValueAsString(Map.of("originalUrl", "https://example.com/i2-a-" + unique("")));
        var created = postJsonWithHeader("/api/links", first, token, "Idempotency-Key", "k-diff");
        assertEquals(200, created.getStatusCode().value(), created.getBody());

        String other = om.writeValueAsString(Map.of("originalUrl", "https://example.com/i2-b-" + unique("")));
        var conflict = postJsonWithHeader("/api/links", other, token, "Idempotency-Key", "k-diff");
        assertEquals(409, conflict.getStatusCode().value(), conflict.getBody());
        assertTrue(conflict.getBody().contains("IDEMPOTENCY_KEY_REUSED"), conflict.getBody());
        assertEquals(1, linkCount(token), "the conflicting request must not mint a second link");
    }

    @Test
    void replaysDoNotBurnCreateQuota() throws Exception {
        String token = registerForToken();
        String json = om.writeValueAsString(Map.of("originalUrl", "https://example.com/i2-q-" + unique("")));
        var first = postJsonWithHeader("/api/links", json, token, "Idempotency-Key", "k-quota");
        assertEquals(200, first.getStatusCode().value(), first.getBody());
        for (int i = 0; i < 5; i++) {
            var replay = postJsonWithHeader("/api/links", json, token, "Idempotency-Key", "k-quota");
            assertEquals(200, replay.getStatusCode().value(), "replay " + i + " must not be throttled");
        }
        // create bucket is 3: one keyed create + two fresh ones fit, the next is a 429.
        // If the five replays had burned quota, the FIRST fresh create would already 429.
        for (int i = 0; i < 2; i++) {
            var fresh = postJson("/api/links",
                    om.writeValueAsString(Map.of("originalUrl", "https://example.com/i2-f" + i + "-" + unique(""))),
                    token);
            assertEquals(200, fresh.getStatusCode().value(), fresh.getBody());
        }
        var throttled = postJson("/api/links",
                om.writeValueAsString(Map.of("originalUrl", "https://example.com/i2-over-" + unique(""))), token);
        assertEquals(429, throttled.getStatusCode().value(), throttled.getBody());
    }

    @Test
    void importReplayReturnsStoredOutcomeAndBurnsNoQuota() throws Exception {
        String token = registerForToken();
        String csv = "original_url\nhttps://example.com/imp-a-" + unique("") + "\nhttps://example.com/imp-b-" + unique("") + "\n";
        var first = importCsv(token, csv, "imp-key-1");
        assertEquals(200, first.getStatusCode().value(), first.getBody());
        assertEquals(2, body(first).path("data").path("successCount").asInt());
        assertEquals(2, linkCount(token));

        var replay = importCsv(token, csv, "imp-key-1");
        assertEquals(200, replay.getStatusCode().value(), replay.getBody());
        assertEquals(body(first).path("data").toString(), body(replay).path("data").toString(),
                "the replay must return the stored outcome verbatim");
        assertEquals(2, linkCount(token), "the replay must not import a second time");

        // import bucket is 2: the keyed import burned one, this fresh import burns
        // the second; a third is a 429 — proving the replay above burned nothing.
        var fresh = importCsv(token, "original_url\nhttps://example.com/imp-c-" + unique("") + "\n", null);
        assertEquals(200, fresh.getStatusCode().value(), fresh.getBody());
        var throttled = importCsv(token, "original_url\nhttps://example.com/imp-d-" + unique("") + "\n", null);
        assertEquals(429, throttled.getStatusCode().value(), throttled.getBody());
    }

    @Test
    void importSameKeyDifferentFileIsA409() throws Exception {
        String token = registerForToken();
        var first = importCsv(token, "original_url\nhttps://example.com/imp-x-" + unique("") + "\n", "imp-key-2");
        assertEquals(200, first.getStatusCode().value(), first.getBody());
        assertEquals(1, linkCount(token));

        var conflict = importCsv(token, "original_url\nhttps://example.com/imp-y-" + unique("") + "\n", "imp-key-2");
        assertEquals(409, conflict.getStatusCode().value(), conflict.getBody());
        assertTrue(conflict.getBody().contains("IDEMPOTENCY_KEY_REUSED"), conflict.getBody());
        assertEquals(1, linkCount(token), "the conflicting file must not be imported");
    }
}
