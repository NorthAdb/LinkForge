package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit M4 contract tests (reversing the probe
 * {@code differentAccessPasswordIsSilentlyReplayed}): the create idempotency
 * fingerprint (format create-v2, settled envelope v3) covers the access
 * password through a pepper-keyed HMAC, so the same key with a different
 * password is a 409 — not a silent replay of the first link. Legacy settled
 * values (v2 envelope / plain code) hold no password material and fail
 * closed for password-bearing retries.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-idempw:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=1000",
        "linkforge.rate-limit.redirect-max=1000",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class IdempotencyPasswordFingerprintIT extends AbstractIntegrationTest {

    private static final String KEY_PREFIX = "linkforge:rl:it-idempw:";

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    private record Registered(long userId, String token) {
    }

    private Registered registerUser() throws Exception {
        var reg = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", unique("t"), "username", unique("u"), "password", "it-pass-123456")), null);
        assertEquals(200, reg.getStatusCode().value(), reg.getBody());
        return new Registered(body(reg).path("data").path("userId").asLong(),
                body(reg).path("data").path("token").asText());
    }

    private ResponseEntity<String> create(String token, String json, String key) {
        return postJsonWithHeader("/api/links", json, token, "Idempotency-Key", key);
    }

    private long linkCount(String token) throws Exception {
        return body(get("/api/links?page=1&size=100", token)).path("data").path("total").asLong();
    }

    private int unlockStatus(String code, String password) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        var resp = rest.exchange("/r/" + code + "/unlock", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>("password=" + URLEncoder.encode(password, StandardCharsets.UTF_8), headers),
                String.class);
        return resp.getStatusCode().value();
    }

    private String idemRedisKey(long userId, String key) {
        return KEY_PREFIX + "idem:" + userId + ":" + key;
    }

    @Test
    void sameKeyDifferentPasswordConflicts() throws Exception {
        Registered user = registerUser();
        String url = "https://example.com/pwfp-" + unique("");
        String first = om.writeValueAsString(Map.of(
                "originalUrl", url, "accessPassword", "it-pass-123456"));
        var created = create(user.token(), first, "pw-diff");
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        String code = body(created).path("data").path("code").asText();

        String otherPassword = om.writeValueAsString(Map.of(
                "originalUrl", url, "accessPassword", "new-pass-123456"));
        var conflict = create(user.token(), otherPassword, "pw-diff");
        assertEquals(409, conflict.getStatusCode().value(), conflict.getBody());
        assertTrue(conflict.getBody().contains("IDEMPOTENCY_KEY_REUSED"), conflict.getBody());
        assertEquals(1, linkCount(user.token()), "the conflict must not mint a second link");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM lf_short_link WHERE code = ?", Integer.class, code).intValue());

        // the FIRST password is still the effective one — the second caller
        // must not silently end up with a link their password does not open
        assertEquals(303, unlockStatus(code, "it-pass-123456"), "the original password still unlocks");
        assertEquals(401, unlockStatus(code, "new-pass-123456"), "the replayed password must not unlock");
    }

    @Test
    void sameKeySamePasswordReplays() throws Exception {
        Registered user = registerUser();
        String json = om.writeValueAsString(Map.of(
                "originalUrl", "https://example.com/pwfp-same-" + unique(""),
                "accessPassword", "it-pass-123456"));
        var first = create(user.token(), json, "pw-same");
        assertEquals(200, first.getStatusCode().value(), first.getBody());
        var replay = create(user.token(), json, "pw-same");
        assertEquals(200, replay.getStatusCode().value(), replay.getBody());
        assertEquals(body(first).path("data").path("code").asText(),
                body(replay).path("data").path("code").asText(), "an identical request replays");
        assertEquals(1, linkCount(user.token()));

        String settled = redis.opsForValue().get(idemRedisKey(user.userId(), "pw-same"));
        assertTrue(settled != null && settled.startsWith("v3:"),
                "new settled values use the v3 envelope: " + settled);
        assertFalse(settled.contains("it-pass-123456"),
                "the settled value must never carry the plaintext password");
    }

    @Test
    void passwordSetVsUnsetConflicts() throws Exception {
        Registered user = registerUser();
        String url = "https://example.com/pwfp-mix-" + unique("");
        String without = om.writeValueAsString(Map.of("originalUrl", url));
        String with = om.writeValueAsString(Map.of(
                "originalUrl", url, "accessPassword", "it-pass-123456"));

        var plainFirst = create(user.token(), without, "pw-mix-a");
        assertEquals(200, plainFirst.getStatusCode().value(), plainFirst.getBody());
        var thenPassword = create(user.token(), with, "pw-mix-a");
        assertEquals(409, thenPassword.getStatusCode().value(), thenPassword.getBody());
        assertTrue(thenPassword.getBody().contains("IDEMPOTENCY_KEY_REUSED"), thenPassword.getBody());

        var passwordFirst = create(user.token(), with, "pw-mix-b");
        assertEquals(200, passwordFirst.getStatusCode().value(), passwordFirst.getBody());
        var thenPlain = create(user.token(), without, "pw-mix-b");
        assertEquals(409, thenPlain.getStatusCode().value(), thenPlain.getBody());
        assertTrue(thenPlain.getBody().contains("IDEMPOTENCY_KEY_REUSED"), thenPlain.getBody());

        assertEquals(2, linkCount(user.token()), "only the two first requests created links");
    }

    /**
     * Legacy settled values (seeded by hand, the way a pre-fix deployment
     * would have left them): a plain code and a v2 envelope. Neither records
     * password material, so a password-bearing retry fails closed (409),
     * while a password-free retry of a v2 value still verifies against the
     * legacy fingerprint and replays.
     */
    @Test
    void legacyValuesFailClosedForPasswordRequests() throws Exception {
        Registered user = registerUser();
        String url = "https://example.com/pwfp-legacy-" + unique("");
        String code = createLink(user.token(), url);
        String noPasswordBody = om.writeValueAsString(Map.of("originalUrl", url));
        String passwordBody = om.writeValueAsString(Map.of(
                "originalUrl", url, "accessPassword", "it-pass-123456"));

        // plain pre-3b value: password-bearing retry fails closed…
        redis.opsForValue().set(idemRedisKey(user.userId(), "legacy-plain"), code, Duration.ofMinutes(5));
        var plainWithPassword = create(user.token(), passwordBody, "legacy-plain");
        assertEquals(409, plainWithPassword.getStatusCode().value(), plainWithPassword.getBody());
        assertTrue(plainWithPassword.getBody().contains("IDEMPOTENCY_KEY_REUSED"), plainWithPassword.getBody());
        // …and a password-free retry still replays the code (pre-3b semantics)
        var plainReplay = create(user.token(), noPasswordBody, "legacy-plain");
        assertEquals(200, plainReplay.getStatusCode().value(), plainReplay.getBody());
        assertEquals(code, body(plainReplay).path("data").path("code").asText());

        // v2 envelope with a fingerprint that does not match this request: 409
        redis.opsForValue().set(idemRedisKey(user.userId(), "legacy-v2-bogus"),
                "v2:" + "0".repeat(64) + ":" + code, Duration.ofMinutes(5));
        var bogusReplay = create(user.token(), noPasswordBody, "legacy-v2-bogus");
        assertEquals(409, bogusReplay.getStatusCode().value(), bogusReplay.getBody());
        assertTrue(bogusReplay.getBody().contains("IDEMPOTENCY_KEY_REUSED"), bogusReplay.getBody());

        // v2 envelope with THIS request's true legacy fingerprint: a
        // password-free retry replays; a password-bearing one fails closed
        // because the legacy record cannot prove password equality.
        redis.opsForValue().set(idemRedisKey(user.userId(), "legacy-v2"),
                "v2:" + legacyFingerprintOfMinimalBody(url) + ":" + code, Duration.ofMinutes(5));
        var legacyReplay = create(user.token(), noPasswordBody, "legacy-v2");
        assertEquals(200, legacyReplay.getStatusCode().value(), legacyReplay.getBody());
        assertEquals(code, body(legacyReplay).path("data").path("code").asText());
        var legacyWithPassword = create(user.token(), passwordBody, "legacy-v2");
        assertEquals(409, legacyWithPassword.getStatusCode().value(), legacyWithPassword.getBody());
        assertTrue(legacyWithPassword.getBody().contains("IDEMPOTENCY_KEY_REUSED"), legacyWithPassword.getBody());

        assertEquals(1, linkCount(user.token()), "no legacy path minted a second link");
    }

    /**
     * The batch-3b create-v1 fingerprint of a body carrying only originalUrl,
     * recomputed from the canonical form (fixed field order, length-prefixed,
     * absent fields as -1:) so the seeded v2 value is a faithful legacy record.
     */
    private static String legacyFingerprintOfMinimalBody(String originalUrl) throws Exception {
        StringBuilder sb = new StringBuilder("create-v1");
        appendLegacyField(sb, "originalUrl", originalUrl);
        appendLegacyField(sb, "customCode", null);
        appendLegacyField(sb, "expireAt", null);
        appendLegacyField(sb, "title", null);
        appendLegacyField(sb, "utmSource", null);
        appendLegacyField(sb, "utmMedium", null);
        appendLegacyField(sb, "utmCampaign", null);
        appendLegacyField(sb, "utmTerm", null);
        appendLegacyField(sb, "utmContent", null);
        appendLegacyField(sb, "tags", null);
        appendLegacyField(sb, "accessPasswordSet", "0");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static void appendLegacyField(StringBuilder sb, String name, String value) {
        sb.append('\n').append(name).append('=');
        if (value == null) {
            sb.append("-1:");
        } else {
            sb.append(value.length()).append(':').append(value);
        }
    }
}
