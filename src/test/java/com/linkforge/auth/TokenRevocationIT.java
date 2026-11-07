package com.linkforge.auth;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.user.entity.UserAccount;
import com.linkforge.user.mapper.UserAccountMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Batch 2a revocation semantics (plan 5.1/5.2): revocation latency is the NEXT
 * request — the JWT filter's DB recheck kills a disabled user's access token
 * and API keys immediately, the refresh endpoint stays reachable with a dead
 * access token attached (A1), and pre-V11 tokens without a tv claim keep
 * working until they expire (A3).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class TokenRevocationIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "it-pass-123456";
    private static final String LINKS = "/api/links?page=1&size=10";

    @Autowired
    private UserAccountMapper userAccountMapper;
    @Value("${linkforge.jwt.secret}")
    private String jwtSecret;

    private record Session(String access, String refresh, long userId, long tenantId, String username) {
    }

    private Session registerSession() throws Exception {
        String username = unique("u");
        ResponseEntity<String> resp = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", unique("rev"), "username", username, "password", PASSWORD)), null);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        var data = body(resp).path("data");
        return new Session(data.path("token").asText(), data.path("refreshToken").asText(),
                data.path("userId").asLong(), data.path("tenantId").asLong(), username);
    }

    @Test
    void disabledUserAccessAndApiKeyDieNextRequest() throws Exception {
        Session session = registerSession();
        ResponseEntity<String> keyResp = postJson("/api/keys",
                om.writeValueAsString(Map.of("name", "revocation-probe")), session.access());
        assertEquals(200, keyResp.getStatusCode().value(), keyResp.getBody());
        String plaintextKey = body(keyResp).path("data").path("key").asText();

        assertEquals(200, get(LINKS, session.access()).getStatusCode().value());
        assertEquals(200, getWithHeader(LINKS, null, "X-API-Key", plaintextKey).getStatusCode().value());

        UserAccount user = userAccountMapper.selectById(session.userId());
        user.setStatus(0);
        userAccountMapper.updateById(user);

        assertEquals(401, get(LINKS, session.access()).getStatusCode().value(),
                "a disabled user's access token must die on the very next request");
        assertEquals(401, getWithHeader(LINKS, null, "X-API-Key", plaintextKey).getStatusCode().value(),
                "a disabled user's API key must die on the very next request");
    }

    @Test
    void refreshWorksWithDeadAccessAttached() throws Exception {
        Session session = registerSession();
        // kill the access token the way logout-all / a reuse kill would: tv bump
        UserAccount user = userAccountMapper.selectById(session.userId());
        user.setTokenVersion((user.getTokenVersion() == null ? 0 : user.getTokenVersion()) + 1);
        userAccountMapper.updateById(user);
        assertEquals(401, get(LINKS, session.access()).getStatusCode().value(),
                "sanity: the access token really is dead");

        ResponseEntity<String> resp = postJson("/api/auth/refresh",
                om.writeValueAsString(Map.of("refreshToken", session.refresh())), session.access());
        assertEquals(200, resp.getStatusCode().value(),
                "refresh must not be gated by the dead access token attached to it: " + resp.getBody());
        String newAccess = body(resp).path("data").path("token").asText();
        assertEquals(200, get(LINKS, newAccess).getStatusCode().value(),
                "the refreshed access token carries the current tv and works");
    }

    @Test
    void legacyTokenWithoutTvStillWorks() throws Exception {
        Session session = registerSession();
        long now = System.currentTimeMillis();
        String legacy = Jwts.builder()
                .subject(String.valueOf(session.userId()))
                .claim("tenantId", session.tenantId())
                .claim("username", session.username())
                .issuedAt(new Date(now))
                .expiration(new Date(now + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();
        assertEquals(200, get(LINKS, legacy).getStatusCode().value(),
                "a pre-V11 token (no tv claim) reads as tv=0 and stays valid until expiry");
    }
}
