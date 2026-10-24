package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.link.entity.ShortLink;
import com.linkforge.link.mapper.ShortLinkMapper;
import com.linkforge.stats.entity.ClickLog;
import com.linkforge.stats.mapper.ClickLogMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
class LinkLifecycleIT extends AbstractIntegrationTest {

    private static final String KEY_PREFIX = "linkforge:code:";

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ShortLinkMapper shortLinkMapper;

    @Autowired
    private ClickLogMapper clickLogMapper;

    @Test
    void createRejectsNonHttpUrl() throws Exception {
        String token = registerForToken();
        var resp = postJson("/api/links",
                om.writeValueAsString(Map.of("originalUrl", "ftp://example.com/x")), token);
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
    }

    @Test
    void createRejectsControlCharAndMalformedUrls() throws Exception {
        String token = registerForToken();
        String[][] bad = {
                {"https://example.com/a\r\nInjected: yes", "CRLF must be rejected at create"},
                {"https://example.com/a b", "space must be rejected at create"},
                {"https://example.com/\u0000x", "NUL must be rejected at create"}};
        for (String[] pair : bad) {
            var resp = postJson("/api/links", om.writeValueAsString(Map.of("originalUrl", pair[0])), token);
            assertEquals(400, resp.getStatusCode().value(), pair[1] + ": " + resp.getBody());
        }
    }

    @Test
    void updateRejectsInvalidUrl() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/valid-first");
        var resp = patchJson("/api/links/" + code,
                om.writeValueAsString(Map.of("originalUrl", "https://example.com/a\r\nb")), token);
        assertEquals(400, resp.getStatusCode().value(), resp.getBody());
    }

    @Test
    void invalidLegacyTargetReturns410WithoutCountingClick() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/legacy-ok");
        ShortLink link = shortLinkMapper.selectOne(new LambdaQueryWrapper<ShortLink>()
                .eq(ShortLink::getCode, code));
        link.setOriginalUrl("https://example.com/a\r\nInjected: yes");
        shortLinkMapper.updateById(link);
        redis.delete(KEY_PREFIX + code);

        var resp = get("/r/" + code, null);
        assertEquals(410, resp.getStatusCode().value(),
                "legacy dirty target must be 410, never 500: " + resp.getBody());

        Long clicks = clickLogMapper.selectCount(new LambdaQueryWrapper<ClickLog>()
                .eq(ClickLog::getLinkId, link.getId()));
        assertEquals(0, clicks, "an invalid target must not be counted as a click");
        assertFalse(Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + code)),
                "an invalid target must not be cached");
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

    @Test
    void updateCanSetExpireAt() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/exp-set");
        var past = patchJson("/api/links/" + code,
                om.writeValueAsString(Map.of("expireAt", LocalDateTime.now().minusMinutes(1).toString())), token);
        assertEquals(400, past.getStatusCode().value(), past.getBody());

        var future = patchJson("/api/links/" + code,
                om.writeValueAsString(Map.of("expireAt", LocalDateTime.now().plusDays(3).toString())), token);
        assertEquals(200, future.getStatusCode().value(), future.getBody());
        assertFalse(body(future).path("data").path("expireAt").asText().isBlank());
    }

    @Test
    void enableRestoresDisabledLink() throws Exception {
        String token = registerForToken();
        String target = "https://example.com/re-enable-" + System.currentTimeMillis();
        String code = createLink(token, target);

        assertEquals(200, postJson("/api/links/" + code + "/disable", "{}", token).getStatusCode().value());
        assertEquals(404, get("/r/" + code, null).getStatusCode().value());

        assertEquals(200, postJson("/api/links/" + code + "/enable", "{}", token).getStatusCode().value());
        var redirected = get("/r/" + code, null);
        assertEquals(302, redirected.getStatusCode().value(), redirected.getBody());
        assertEquals(target, redirected.getHeaders().getLocation().toString());
    }

    @Test
    void deleteRemovesLinkEverywhere() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/del-" + System.currentTimeMillis());

        assertEquals(200, delete("/api/links/" + code, token).getStatusCode().value());

        assertFalse(Boolean.TRUE.equals(redis.hasKey(KEY_PREFIX + code)), "cache must be evicted after delete");
        assertEquals(404, get("/r/" + code, null).getStatusCode().value());
        assertEquals(404, get("/api/links/" + code, token).getStatusCode().value());
        var list = get("/api/links?page=1&size=100", token);
        assertEquals(0, body(list).path("data").path("total").asLong());
    }

    @Test
    void unauthenticatedManagementCallReturns401() {
        var resp = get("/api/links?page=1&size=10", null);
        assertEquals(401, resp.getStatusCode().value(), resp.getBody());
    }
}
