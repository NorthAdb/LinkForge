package com.linkforge.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.AbstractIntegrationTest;
import com.linkforge.user.entity.Tenant;
import com.linkforge.user.entity.UserAccount;
import com.linkforge.user.mapper.TenantMapper;
import com.linkforge.user.mapper.UserAccountMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-flow:",
        "linkforge.rate-limit.register-max=1000"
})
@ActiveProfiles("test")
class RegisterFlowTest extends AbstractIntegrationTest {

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private UserAccountMapper userAccountMapper;

    @Test
    void concurrentSameTenantNameLeavesSingleTenantAndUser() throws Exception {
        String tenantName = unique("race");
        Callable<ResponseEntity<String>> call = () -> postJson("/api/auth/register",
                om.writeValueAsString(Map.of("tenantName", tenantName,
                        "username", unique("u"), "password", "it-pass-123456")), null);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> statuses;
        try {
            statuses = pool.invokeAll(List.of(call, call)).stream()
                    .map(f -> {
                        try {
                            return f.get().getStatusCode().value();
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }).toList();
        } finally {
            pool.shutdownNow();
        }
        assertEquals(2, statuses.size());
        assertTrue(statuses.contains(200), "exactly one winner expected: " + statuses);
        assertTrue(statuses.contains(409), "the loser must get a clean 409: " + statuses);

        Tenant tenant = tenantMapper.selectOne(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getName, tenantName));
        assertEquals(1, tenantMapper.selectCount(new LambdaQueryWrapper<Tenant>()
                .eq(Tenant::getName, tenantName)), "no orphan tenant rows");
        assertEquals(1, userAccountMapper.selectCount(new LambdaQueryWrapper<UserAccount>()
                        .eq(UserAccount::getTenantId, tenant.getId())),
                "exactly one user under the winning tenant");
    }

    @Test
    void registerReturnsUsableToken() throws Exception {
        String token = registerForToken();
        var resp = get("/api/links?page=1&size=10", token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
    }

    @Test
    void duplicateTenantNameRejected() throws Exception {
        String tenantName = unique("dup");
        String first = om.writeValueAsString(Map.of("tenantName", tenantName,
                "username", unique("u"), "password", "it-pass-123456"));
        var ok = postJson("/api/auth/register", first, null);
        assertEquals(200, ok.getStatusCode().value(), ok.getBody());

        String second = om.writeValueAsString(Map.of("tenantName", tenantName,
                "username", unique("u"), "password", "it-pass-123456"));
        var conflict = postJson("/api/auth/register", second, null);
        assertEquals(409, conflict.getStatusCode().value(), conflict.getBody());
        assertEquals(409, body(conflict).path("code").asInt());
    }

    @Test
    void weakPasswordRejected() throws Exception {
        String json = om.writeValueAsString(Map.of("tenantName", unique("wk"),
                "username", unique("u"), "password", "short7"));
        var resp = postJson("/api/auth/register", json, null);
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
    }

    @Test
    void invalidTenantCharsRejected() throws Exception {
        String json = om.writeValueAsString(Map.of("tenantName", "bad!name",
                "username", unique("u"), "password", "it-pass-123456"));
        var resp = postJson("/api/auth/register", json, null);
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
    }

    @Test
    void malformedBodyRejected() {
        var resp = postJson("/api/auth/register", "{not-json", null);
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
        assertTrue(resp.getBody().contains("malformed"), resp.getBody());
    }
}
