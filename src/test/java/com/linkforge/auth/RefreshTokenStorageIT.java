package com.linkforge.auth;
import com.linkforge.auth.mapper.RefreshTokenMapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch 2a token hygiene (plan 5.1/5.2): the database holds only SHA-256 hashes
 * of refresh tokens — the raw value appears in the issuing response and nowhere
 * in storage, so a database leak is not a session leak.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class RefreshTokenStorageIT extends AbstractIntegrationTest {

    @Autowired
    private RefreshTokenMapper refreshTokenMapper;

    @Test
    void onlyTheHashIsStored() throws Exception {
        ResponseEntity<String> resp = postJson("/api/auth/register", om.writeValueAsString(Map.of(
                "tenantName", unique("store"), "username", unique("u"),
                "password", "it-pass-123456")), null);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        var data = body(resp).path("data");
        String raw = data.path("refreshToken").asText();
        long userId = data.path("userId").asLong();
        assertFalse(raw.isBlank());

        List<RefreshToken> rows = refreshTokenMapper.selectList(new LambdaQueryWrapper<RefreshToken>()
                .eq(RefreshToken::getUserId, userId));
        assertEquals(1, rows.size(), "one login = one refresh row");
        RefreshToken row = rows.get(0);
        assertTrue(row.getTokenHash().matches("[0-9a-f]{64}"),
                "token_hash must be a SHA-256 hex string: " + row.getTokenHash());
        assertEquals(RefreshRotationIT.sha256Hex(raw), row.getTokenHash(),
                "the stored hash must correspond to the issued raw token");
        assertFalse(row.getTokenHash().equals(raw), "the raw token must never be the stored value");
        assertFalse(row.getFamilyId().contains(raw));
        assertEquals(0, refreshTokenMapper.selectCount(new LambdaQueryWrapper<RefreshToken>()
                        .eq(RefreshToken::getTokenHash, raw)),
                "no row may be keyed by the raw token itself");
    }
}
