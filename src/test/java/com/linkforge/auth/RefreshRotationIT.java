package com.linkforge.auth;
import com.linkforge.auth.mapper.RefreshTokenMapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Batch 2a rotation contract (plan 5.1): refresh rotates atomically, a replay of
 * the just-rotated token inside the 10s grace is a plain 401 that leaves the
 * family alive, and a replay outside the grace is treated as theft — the whole
 * family dies and token_version bumps, killing outstanding access tokens.
 * Time is controlled by backdating DB rows, never by sleeping (plan A7 rule).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RefreshRotationIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "it-pass-123456";

    @Autowired
    private RefreshTokenMapper refreshTokenMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private record Session(String access, String refresh, long userId) {
    }

    private Session registerSession() throws Exception {
        ResponseEntity<String> resp = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", unique("rot"), "username", unique("u"), "password", PASSWORD)), null);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        var data = body(resp).path("data");
        return new Session(data.path("token").asText(), data.path("refreshToken").asText(),
                data.path("userId").asLong());
    }

    private ResponseEntity<String> refreshCall(String rawRefresh) throws Exception {
        return postJson("/api/auth/refresh",
                om.writeValueAsString(Map.of("refreshToken", rawRefresh)), null);
    }

    private RefreshToken rowOf(String rawRefresh) {
        return refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshToken>()
                .eq(RefreshToken::getTokenHash, sha256Hex(rawRefresh)));
    }

    static String sha256Hex(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void refreshRotatesAndIssuesNewPair() throws Exception {
        Session session = registerSession();

        ResponseEntity<String> resp = refreshCall(session.refresh());
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        var data = body(resp).path("data");
        String newAccess = data.path("token").asText();
        String newRefresh = data.path("refreshToken").asText();
        assertNotNull(newAccess);
        assertNotEquals(session.refresh(), newRefresh, "rotation must issue a new refresh token");

        RefreshToken oldRow = rowOf(session.refresh());
        assertNotNull(oldRow.getRevokedAt(), "the presented row must be revoked by rotation");
        assertEquals(RefreshToken.CAUSE_ROTATED, oldRow.getRevokeCause());
        RefreshToken newRow = rowOf(newRefresh);
        assertEquals(newRow.getId(), oldRow.getReplacedById(),
                "the rotated row must point at its successor");
        assertEquals(oldRow.getFamilyId(), newRow.getFamilyId(), "rotation stays in the family");
        assertEquals(200, get("/api/links?page=1&size=10", newAccess).getStatusCode().value());
    }

    @Test
    void reuseWithinGraceKeepsFamily() throws Exception {
        Session session = registerSession();
        ResponseEntity<String> first = refreshCall(session.refresh());
        assertEquals(200, first.getStatusCode().value(), first.getBody());
        String liveRefresh = body(first).path("data").path("refreshToken").asText();

        // immediate replay of the just-rotated token: benign race, plain 401
        ResponseEntity<String> replay = refreshCall(session.refresh());
        assertEquals(401, replay.getStatusCode().value(), replay.getBody());

        // …but the family survives: the current token still rotates fine
        ResponseEntity<String> next = refreshCall(liveRefresh);
        assertEquals(200, next.getStatusCode().value(),
                "family must survive a within-grace replay: " + next.getBody());
    }

    @Test
    void reuseAfterGraceKillsFamilyAndAccess() throws Exception {
        Session session = registerSession();
        ResponseEntity<String> first = refreshCall(session.refresh());
        assertEquals(200, first.getStatusCode().value(), first.getBody());
        var pair = body(first).path("data");
        String liveAccess = pair.path("token").asText();
        String liveRefresh = pair.path("refreshToken").asText();

        // the rotated-away token's revocation is suddenly 60s old: past the grace.
        // Computed on the JVM clock — the same clock the service compares against —
        // so DB/JVM timezone skew cannot leak into the assertion.
        jdbcTemplate.update("UPDATE lf_refresh_token SET revoked_at = ? WHERE token_hash = ?",
                java.time.LocalDateTime.now().minusSeconds(60), sha256Hex(session.refresh()));

        ResponseEntity<String> replay = refreshCall(session.refresh());
        assertEquals(401, replay.getStatusCode().value(), replay.getBody());

        assertEquals(401, refreshCall(liveRefresh).getStatusCode().value(),
                "the whole family must be dead after a reuse kill");
        assertEquals(401, get("/api/links?page=1&size=10", liveAccess).getStatusCode().value(),
                "token_version bump must kill the outstanding access token on its next request");
        assertEquals(401, get("/api/links?page=1&size=10", session.access()).getStatusCode().value(),
                "the pre-rotation access token dies by the same bump");
    }
}
