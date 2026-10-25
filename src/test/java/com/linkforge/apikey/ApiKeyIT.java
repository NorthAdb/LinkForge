package com.linkforge.apikey;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B-wave2 B4: hashed API keys with per-key quotas. One key = one creator identity:
 * requests act as that user (ROLE_API_KEY), the key surface itself stays JWT-only,
 * revocation is effective on the very next request (no auth cache).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-apikey:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=1000",
        "linkforge.rate-limit.redirect-max=1000",
        "linkforge.api-key.max-active-per-user=3",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class ApiKeyIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private String createKey(String token, String name, Integer perMinute) throws Exception {
        var request = new java.util.HashMap<String, Object>();
        request.put("name", name);
        if (perMinute != null) {
            request.put("quotaPerMinute", perMinute);
        }
        var resp = postJson("/api/keys", om.writeValueAsString(request), token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        return body(resp).path("data").path("key").asText();
    }

    @Test
    void keyLifecycleHashOnlyStorageAndCreatorIdentity() throws Exception {
        String token = registerForToken();

        var created = postJson("/api/keys", om.writeValueAsString(Map.of("name", "ci-integration")), token);
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        String plaintext = body(created).path("data").path("key").asText();
        String prefix = body(created).path("data").path("prefix").asText();
        assertTrue(plaintext.startsWith("lfk_") && plaintext.contains("_"), plaintext);
        assertEquals(prefix, plaintext.substring(0, plaintext.indexOf('_', 4)));

        // list never carries the plaintext or the hash
        var list = get("/api/keys", token);
        String listBody = list.getBody();
        assertFalse(listBody.contains(plaintext), "plaintext must never appear in a listing");
        assertFalse(listBody.contains("keyHash") || listBody.contains("key_hash"), listBody);
        assertTrue(listBody.contains(prefix));

        // only the hash lives in the DB
        var rows = jdbc.queryForList("SELECT key_hash, key_prefix FROM lf_api_key WHERE key_prefix = ?", prefix);
        assertEquals(1, rows.size());
        assertEquals(sha256Hex(plaintext), String.valueOf(rows.get(0).get("key_hash")));

        // the key acts as its creator on the link surface
        var createLink = postJsonWithKey("/api/links",
                om.writeValueAsString(Map.of("originalUrl", "https://example.com/via-key")), plaintext);
        assertEquals(200, createLink.getStatusCode().value(), createLink.getBody());
        String code = body(createLink).path("data").path("code").asText();
        Long createdBy = jdbc.queryForObject("SELECT created_by FROM lf_short_link WHERE code = ?", Long.class, code);
        Long keyUserId = jdbc.queryForObject(
                "SELECT user_id FROM lf_api_key WHERE key_prefix = ?", Long.class, prefix);
        assertEquals(keyUserId, createdBy, "a key-authenticated create must own its creator id");

        assertEquals(200, getWithKey("/api/links/" + code + "/stats?days=7", plaintext).getStatusCode().value());
        assertEquals(200, getWithKey("/api/links?page=1&size=10", plaintext).getStatusCode().value());

        // another user's key cannot see this link (per-user ownership survives)
        String otherToken = registerForToken();
        String otherKey = createKey(otherToken, "other", null);
        assertEquals(404, getWithKey("/api/links/" + code, otherKey).getStatusCode().value());
    }

    @Test
    void revokedKeyFailsOnTheVeryNextRequestAndForgedKeysAreInvalid() throws Exception {
        String token = registerForToken();
        var created = postJson("/api/keys", om.writeValueAsString(Map.of("name", "short-lived")), token);
        String plaintext = body(created).path("data").path("key").asText();
        long id = body(created).path("data").path("id").asLong();

        assertEquals(200, getWithKey("/api/links?page=1&size=1", plaintext).getStatusCode().value());

        assertEquals(200, delete("/api/keys/" + id, token).getStatusCode().value());
        assertEquals(401, getWithKey("/api/links?page=1&size=1", plaintext).getStatusCode().value(),
                "revocation must be effective on the next request (no auth cache)");

        assertEquals(401, getWithKey("/api/links?page=1&size=1", "lfk_deadbeef_" + "x".repeat(32)).getStatusCode().value());
        assertEquals(401, getWithKey("/api/links?page=1&size=1", "not-even-a-key").getStatusCode().value());
    }

    @Test
    void jwtAndApiKeyTogetherAreAmbiguous() throws Exception {
        String token = registerForToken();
        String key = createKey(token, "dual", null);

        var both = postJsonWithKey("/api/links",
                om.writeValueAsString(Map.of("originalUrl", "https://example.com/dual")), key, token);
        assertEquals(400, both.getStatusCode().value(), both.getBody());
        assertTrue(both.getBody().contains("ambiguous"));
    }

    @Test
    void keySurfaceIsForbiddenToKeysButHealthStaysOpen() throws Exception {
        String token = registerForToken();
        String key = createKey(token, "limited", null);

        for (String uri : new String[]{"/api/keys", "/api/webhooks/endpoint", "/actuator/metrics"}) {
            assertEquals(403, getWithKey(uri, key).getStatusCode().value(), uri);
        }
        assertEquals(200, rest.getForEntity("/actuator/health", String.class).getStatusCode().value());

        // the same URIs keep working with the JWT
        assertEquals(200, get("/api/keys", token).getStatusCode().value());
    }

    @Test
    void perKeyMinuteQuotaReturns429WithRetryAfterAndSpareOthers() throws Exception {
        String token = registerForToken();
        String tiny = createKey(token, "tiny", 3);
        String spare = createKey(token, "spare", null);

        for (int i = 0; i < 3; i++) {
            var ok = postJsonWithKey("/api/links",
                    om.writeValueAsString(Map.of("originalUrl", "https://example.com/q-" + i)), tiny);
            assertEquals(200, ok.getStatusCode().value(), ok.getBody());
        }
        // let the oldest minute-window entry age ≥2s so the sliding window's Retry-After
        // (oldest + 60s − now) drops below 60; the old hardcoded 60 never does
        Thread.sleep(2000);
        var rejected = postJsonWithKey("/api/links",
                om.writeValueAsString(Map.of("originalUrl", "https://example.com/q-4")), tiny);
        assertEquals(429, rejected.getStatusCode().value(), rejected.getBody());
        String retryAfter = rejected.getHeaders().getFirst("Retry-After");
        assertNotNull(retryAfter, "429 must carry Retry-After");
        int seconds = Integer.parseInt(retryAfter.trim());
        assertTrue(seconds >= 1 && seconds <= 58,
                "minute-quota Retry-After must reflect the oldest entry, not a hardcoded 60: " + seconds);

        // JWT and other keys are untouched
        var viaJwt = postJson("/api/links",
                om.writeValueAsString(Map.of("originalUrl", "https://example.com/q-jwt")), token);
        assertEquals(200, viaJwt.getStatusCode().value(), viaJwt.getBody());
        var viaSpare = postJsonWithKey("/api/links",
                om.writeValueAsString(Map.of("originalUrl", "https://example.com/q-spare")), spare);
        assertEquals(200, viaSpare.getStatusCode().value(), viaSpare.getBody());
    }

    @Test
    void activeKeyLimitIsEnforced() throws Exception {
        String token = registerForToken();
        for (int i = 0; i < 3; i++) {
            assertEquals(200, postJson("/api/keys", om.writeValueAsString(Map.of("name", "k" + i)), token)
                    .getStatusCode().value());
        }
        var fourth = postJson("/api/keys", om.writeValueAsString(Map.of("name", "k4")), token);
        assertEquals(400, fourth.getStatusCode().value(), fourth.getBody());
        assertTrue(fourth.getBody().contains("limit"));

        // revoking frees a slot
        long firstId = body(get("/api/keys", token)).path("data").get(0).path("id").asLong();
        assertEquals(200, delete("/api/keys/" + firstId, token).getStatusCode().value());
        assertEquals(200, postJson("/api/keys", om.writeValueAsString(Map.of("name", "k-after-revoke")), token)
                .getStatusCode().value());
    }

    private org.springframework.http.ResponseEntity<String> postJsonWithKey(
            String uri, String json, String apiKey) {
        return postJsonWithKey(uri, json, apiKey, null);
    }

    private org.springframework.http.ResponseEntity<String> postJsonWithKey(
            String uri, String json, String apiKey, String bearerToken) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set(com.linkforge.security.ApiKeyAuthFilter.API_KEY_HEADER, apiKey);
        if (bearerToken != null) {
            headers.setBearerAuth(bearerToken);
        }
        return rest.exchange(uri, org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(json, headers), String.class);
    }

    private org.springframework.http.ResponseEntity<String> getWithKey(String uri, String apiKey) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.set(com.linkforge.security.ApiKeyAuthFilter.API_KEY_HEADER, apiKey);
        return rest.exchange(uri, org.springframework.http.HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers), String.class);
    }

    private static String sha256Hex(String input) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
