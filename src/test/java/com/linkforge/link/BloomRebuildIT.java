package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.link.support.CodeBloomFilter;
import com.linkforge.user.entity.UserAccount;
import com.linkforge.user.mapper.UserAccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C2 wave B (D-C2-9..14): Bloom rebuild. A scheduled or ADMIN-triggered rebuild
 * rebuilds the filter from {@code selectAllCodes()} (which deliberately INCLUDES
 * logically deleted codes — classic Bloom cannot retract, the DB 404s them) via a
 * bypass-and-atomic-swap, gated to tenant ADMINs only, single-flighted per process.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-bloomrb:",
        "linkforge.rate-limit.register-max=1000",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class BloomRebuildIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "it-pass-123456";
    private static final String API_KEY_HEADER = "X-API-Key";

    private record Founder(String token, long userId, long tenantId, String tenantName) {
    }

    @Autowired
    private CodeBloomFilter codeBloomFilter;
    @Autowired
    private UserAccountMapper userAccountMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Founder registerFounder() throws Exception {
        String json = om.writeValueAsString(Map.of(
                "tenantName", unique("rb-tenant"),
                "username", unique("rb-owner"),
                "password", PASSWORD));
        ResponseEntity<String> resp = postJson("/api/auth/register", json, null);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        var data = body(resp).path("data");
        return new Founder(data.path("token").asText(), data.path("userId").asLong(),
                data.path("tenantId").asLong(), data.path("tenantName").asText());
    }

    private String seedMemberAndLogin(Founder founder) throws Exception {
        UserAccount member = new UserAccount();
        member.setTenantId(founder.tenantId());
        member.setUsername(unique("rb-member"));
        member.setPasswordHash(passwordEncoder.encode(PASSWORD));
        member.setStatus(1);
        member.setDeleted(0);
        member.setCreatedAt(LocalDateTime.now());
        member.setUpdatedAt(LocalDateTime.now());
        userAccountMapper.insert(member);
        String json = om.writeValueAsString(Map.of(
                "tenantName", founder.tenantName(),
                "username", member.getUsername(),
                "password", PASSWORD));
        ResponseEntity<String> resp = postJson("/api/auth/login", json, null);
        assertEquals(200, resp.getStatusCode().value(), "member login failed: " + resp.getBody());
        return body(resp).path("data").path("token").asText();
    }

    @Test
    void adminTriggersRebuildAndGatingKeepsMembersAndKeysOut() throws Exception {
        Founder founder = registerFounder();
        String code = createLink(founder.token(), "https://example.com/rb-" + System.currentTimeMillis());

        ResponseEntity<String> rebuilt = postJson("/api/admin/bloom/rebuild", "{}", founder.token());
        assertEquals(200, rebuilt.getStatusCode().value(), "ADMIN JWT must trigger the rebuild: "
                + rebuilt.getBody());
        assertTrue(codeBloomFilter.isReady(), "the filter must stay loaded after a rebuild");
        assertTrue(codeBloomFilter.approximateElementCount() >= 1,
                "the rebuilt filter must contain the created code");
        assertTrue(codeBloomFilter.mightContain(code), "rebuild must reseed from the DB");

        String memberToken = seedMemberAndLogin(founder);
        assertEquals(403, postJson("/api/admin/bloom/rebuild", "{}", memberToken).getStatusCode().value(),
                "MEMBER must be 403 on the rebuild trigger");

        var keyResp = postJson("/api/keys", om.writeValueAsString(Map.of("name", "rb-key")), founder.token());
        String plaintext = body(keyResp).path("data").path("key").asText();
        ResponseEntity<String> withKey = postJsonWithHeader("/api/admin/bloom/rebuild", "{}",
                null, API_KEY_HEADER, plaintext);
        assertEquals(403, withKey.getStatusCode().value(),
                "an API key must be 403 on the rebuild trigger, whatever its scope");
    }

    @Test
    void rebuildKeepsLogicallyDeletedCodesPositive() throws Exception {
        Founder founder = registerFounder();
        String code = createLink(founder.token(), "https://example.com/rb-del-" + System.currentTimeMillis());
        assertEquals(200, delete("/api/links/" + code, founder.token()).getStatusCode().value());

        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM lf_short_link WHERE code = ?", Long.class, code);
        assertEquals(1L, rows, "delete is logical: the row and its uk_code stay");

        ResponseEntity<String> rebuilt = postJson("/api/admin/bloom/rebuild", "{}", founder.token());
        assertEquals(200, rebuilt.getStatusCode().value(), rebuilt.getBody());
        assertTrue(codeBloomFilter.mightContain(code),
                "D-C2-11: rebuild seeds selectAllCodes() including logically deleted codes; "
                        + "the DB filters them to 404");
    }
}
