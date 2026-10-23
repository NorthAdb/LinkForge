package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-life:",
        "linkforge.rate-limit.register-max=1000"
})
@ActiveProfiles("test")
class LinkLifecycleTest extends AbstractIntegrationTest {

    private static final String KEY_PREFIX = "linkforge:code:";

    @Autowired
    private StringRedisTemplate redis;

    @Test
    void createRejectsNonHttpUrl() throws Exception {
        String token = registerForToken();
        var resp = postJson("/api/links",
                om.writeValueAsString(Map.of("originalUrl", "ftp://example.com/x")), token);
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
    }

    @Test
    void createRejectsPastExpireAt() throws Exception {
        String token = registerForToken();
        var resp = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://example.com/a",
                "expireAt", LocalDateTime.now().minusMinutes(1).toString())), token);
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
    }

    @Test
    void listIsPaginatedAndOwned() throws Exception {
        String token = registerForToken();
        for (int i = 0; i < 3; i++) {
            createLink(token, "https://example.com/paged-" + i);
        }
        var page1 = get("/api/links?page=1&size=2", token);
        assertEquals(200, page1.getStatusCode().value(), page1.getBody());
        var data = body(page1).path("data");
        assertEquals(2, data.path("records").size());
        assertEquals(3, data.path("total").asLong());
        assertEquals(2, data.path("size").asLong());
    }

    @Test
    void detailReturnsFields() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/detail");
        var resp = get("/api/links/" + code, token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        var data = body(resp).path("data");
        assertEquals(code, data.path("code").asText());
        assertEquals("https://example.com/detail", data.path("originalUrl").asText());
        assertEquals(1, data.path("status").asInt());
        assertFalse(data.path("createdAt").asText().isBlank(), resp.getBody());
    }

    @Test
    void updateWritesThroughCacheAndRedirectFollows() throws Exception {
        String token = registerForToken();
        String first = "https://example.com/before-" + System.currentTimeMillis();
        String second = "https://example.com/after-" + System.currentTimeMillis();
        String code = createLink(token, first);

        var redirected = get("/r/" + code, null);
        assertEquals(302, redirected.getStatusCode().value(), redirected.getBody());
        assertEquals(first, redirected.getHeaders().getLocation().toString());

        var patched = patchJson("/api/links/" + code,
                om.writeValueAsString(Map.of("originalUrl", second)), token);
        assertEquals(200, patched.getStatusCode().value(), patched.getBody());
        assertEquals(second, body(patched).path("data").path("originalUrl").asText());

        assertEquals(second, redis.opsForValue().get(KEY_PREFIX + code),
                "write-through must cache the new URL immediately");

        var redirectedAgain = get("/r/" + code, null);
        assertEquals(302, redirectedAgain.getStatusCode().value(), redirectedAgain.getBody());
        assertEquals(second, redirectedAgain.getHeaders().getLocation().toString());

        // after the delayed stale-clear fires, a fresh read must still resolve to the new URL
        Thread.sleep(1300);
        var afterClear = get("/r/" + code, null);
        assertEquals(302, afterClear.getStatusCode().value(), afterClear.getBody());
        assertEquals(second, afterClear.getHeaders().getLocation().toString(),
                "location must stay consistent after the delayed double delete");
        assertEquals(second, redis.opsForValue().get(KEY_PREFIX + code));
    }

    @Test
    void disableStopsRedirectAndEvictsCache() throws Exception {
        String token = registerForToken();
        String target = "https://example.com/disable-" + System.currentTimeMillis();
        String code = createLink(token, target);

        var disabled = postJson("/api/links/" + code + "/disable", "{}", token);
        assertEquals(200, disabled.getStatusCode().value(), disabled.getBody());

        assertFalse(Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + code)), "cache must be evicted after disable");
        var rejected = get("/r/" + code, null);
        assertEquals(404, rejected.getStatusCode().value(), rejected.getBody());

        var detail = get("/api/links/" + code, token);
        assertEquals(0, body(detail).path("data").path("status").asInt());
    }

    @Test
    void expiredLinkReturns410() throws Exception {
        String token = registerForToken();
        String target = "https://example.com/exp-" + System.currentTimeMillis();
        String json = om.writeValueAsString(Map.of(
                "originalUrl", target,
                "expireAt", LocalDateTime.now().plusSeconds(2).toString()));
        var created = postJson("/api/links", json, token);
        assertEquals(200, created.getStatusCode().value(), created.getBody());
        String code = body(created).path("data").path("code").asText();

        var live = get("/r/" + code, null);
        assertEquals(302, live.getStatusCode().value(), live.getBody());

        Thread.sleep(2600);
        var expired = get("/r/" + code, null);
        assertEquals(410, expired.getStatusCode().value(), expired.getBody());
        assertEquals(410, body(expired).path("code").asInt());
    }

    @Test
    void updateWithNoFieldsRejected() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/noop");
        var resp = patchJson("/api/links/" + code, "{}", token);
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
    }
}
