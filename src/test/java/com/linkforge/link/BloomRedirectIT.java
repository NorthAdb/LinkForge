package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.config.LinkForgeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A9: the redirect hot path's Bloom gate. A never-created code must 404 via the
 * Bloom rejection counter (no DB row could exist), while created codes — including
 * disabled ones, whose row still exists — resolve through the unchanged cache+DB
 * path. Toggle-off restores the exact A8 behaviour (404 via DB, no bloom counter).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-bloom:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=200",
        "linkforge.rate-limit.redirect-max=1000"
})
@ActiveProfiles("test")
class BloomRedirectIT extends AbstractIntegrationTest {

    @Autowired
    private LinkForgeProperties properties;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private com.linkforge.link.mapper.ShortLinkMapper shortLinkMapper;

    @Autowired
    private com.linkforge.link.support.UrlLocalCache urlLocalCache;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Test
    void dirtyRedisValueNeverBackfillsTheLocalCache() throws Exception {
        // Bw2-N1: the L1 backfill sits after BOTH validations — a poisoned Redis value
        // with a valid row must 410 and leave the L1 empty for that code.
        String token = registerForToken();
        String code = createLink(token, "https://example.com/l1-clean");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());

        stringRedisTemplate.opsForValue().set("linkforge:code:" + code, "javascript:alert(1)");
        urlLocalCache.invalidate(code); // force the next read onto the poisoned Redis value

        assertEquals(410, get("/r/" + code, null).getStatusCode().value(),
                "the dirty target must be rejected no matter where it came from");
        assertNull(urlLocalCache.getIfPresent(code), "a value rejected by the renderable check must not enter L1");
    }

    @Test
    void redisOnlyHitBackfillsTheLocalCache() throws Exception {
        // Bw1-N1: after the 10s L1 TTL a hot code keeps hitting Redis; that hit must
        // backfill the L1 (once every row check passed) instead of looping on misses.
        String token = registerForToken();
        String code = createLink(token, "https://example.com/l1-backfill");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());

        urlLocalCache.invalidate(code); // simulate the L1 TTL expiring
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());
        assertEquals("https://example.com/l1-backfill", urlLocalCache.getIfPresent(code),
                "the Redis-only hit must have backfilled the L1");
    }

    private double rejectedCount() {
        var counter = meterRegistry.find("linkforge.bloom.rejected").counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void unknownCodeRejectedPreDbAndCreatedCodesStillRedirect() throws Exception {
        String token = registerForToken();

        double before = rejectedCount();
        assertEquals(404, get("/r/" + unique("nosuch"), null).getStatusCode().value());
        assertTrue(rejectedCount() > before, "unknown code must be bloom-rejected");
        assertEquals(404, head("/r/" + unique("nosuch")).getStatusCode().value(),
                "HEAD shares the GET resolution, Bloom gate included");

        String code = createLink(token, "https://example.com/bloom-hit");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());

        // disable keeps the row (and the Bloom entry); the DB state check must still win
        assertEquals(200, postJson("/api/links/" + code + "/disable", "{}", token).getStatusCode().value());
        assertEquals(404, get("/r/" + code, null).getStatusCode().value(),
                "bloom must not wave a disabled link through");
    }

    @Test
    void bloomDisabledFallsThroughToUnchangedDbPath() throws Exception {
        properties.getBloom().setEnabled(false);
        try {
            String token = registerForToken();

            double before = rejectedCount();
            assertEquals(404, get("/r/" + unique("nosuch"), null).getStatusCode().value());
            assertEquals(before, rejectedCount(), "disabled bloom must not count rejections");

            String code = createLink(token, "https://example.com/bloom-off");
            assertEquals(302, get("/r/" + code, null).getStatusCode().value());
        } finally {
            properties.getBloom().setEnabled(true);
        }
    }

    @Test
    void caseVariantsAreDistinctShortCodes() throws Exception {
        // A9 audit S2 / V4: short codes match exact-case only, both behind the Bloom
        // gate (negative pre-DB) and in the DB itself (utf8mb4_0900_bin).
        String token = registerForToken();
        String mixed = "Aa" + Long.toString(System.nanoTime(), 36);
        String lower = mixed.toLowerCase();

        assertEquals(mixed, createCustomLink(token, mixed, "https://example.com/case-mixed"));

        double before = rejectedCount();
        assertEquals(404, get("/r/" + lower, null).getStatusCode().value(),
                "lower-case variant must not resolve while only the exact case exists");
        assertTrue(rejectedCount() > before, "case-variant miss must be bloom-rejected pre-DB");

        // the variant is a genuinely different code: creating it must succeed (no 409)
        assertEquals(lower, createCustomLink(token, lower, "https://example.com/case-lower"));
        assertEquals(302, get("/r/" + mixed, null).getStatusCode().value());
        assertEquals(302, get("/r/" + lower, null).getStatusCode().value());
    }

    private double counter(String name) {
        var c = meterRegistry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }

    @Test
    void bloomNegativePathNeverWritesTheAbsentCache() throws Exception {
        double put0 = counter("linkforge.cache.absent.put");

        assertEquals(404, get("/r/" + unique("nosuch"), null).getStatusCode().value());

        assertEquals(put0, counter("linkforge.cache.absent.put"),
                "bloom-negative codes must 404 pre-DB and never enter the negative cache");
    }

    @Test
    void disableDoesNotEnterAbsentAndEnableRecoversImmediately() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/absent-disable");
        double put0 = counter("linkforge.cache.absent.put");

        assertEquals(200, postJson("/api/links/" + code + "/disable", "{}", token).getStatusCode().value());
        assertEquals(404, get("/r/" + code, null).getStatusCode().value());
        assertEquals(put0, counter("linkforge.cache.absent.put"),
                "a disabled row still exists — its 404 must not be negatively cached");
        assertEquals(200, postJson("/api/links/" + code + "/enable", "{}", token).getStatusCode().value());
        assertEquals(302, get("/r/" + code, null).getStatusCode().value(),
                "enable must take effect immediately, not after any absent TTL");
    }

    @Test
    void expiredDoesNotEnterAbsent() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/absent-expired");

        // expire the row directly: DATETIME(0) rounds fractional expireAt up, so a
        // sleep-past-create-time race can outwait a 1s expiry (CI-proven flaky)
        com.linkforge.link.entity.ShortLink row = shortLinkMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.linkforge.link.entity.ShortLink>()
                        .eq(com.linkforge.link.entity.ShortLink::getCode, code));
        row.setExpireAt(java.time.LocalDateTime.now().minusSeconds(1));
        shortLinkMapper.updateById(row);

        double put0 = counter("linkforge.cache.absent.put");
        assertEquals(410, get("/r/" + code, null).getStatusCode().value());
        assertEquals(put0, counter("linkforge.cache.absent.put"),
                "expiry is not absence — the row still exists, so no negative cache entry");
    }

    @Test
    void deletedCodeWritesAbsentAndRepeatMissesSkipTheDb() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/absent-deleted");
        double put0 = counter("linkforge.cache.absent.put");
        double hit0 = counter("linkforge.cache.absent.hit");

        assertEquals(200, delete("/api/links/" + code, token).getStatusCode().value());
        assertEquals(404, get("/r/" + code, null).getStatusCode().value());
        assertEquals(1.0, counter("linkforge.cache.absent.put") - put0,
                "logical delete -> DB null -> negative cache entry");
        assertEquals(404, get("/r/" + code, null).getStatusCode().value());
        assertTrue(counter("linkforge.cache.absent.hit") > hit0, "the repeat miss must be served by the absent entry");
    }

    @Test
    void localCacheServesRepeatReadsAndInvalidatesOnUpdate() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/l1-v1");

        assertEquals(302, get("/r/" + code, null).getStatusCode().value());
        double hit0 = counter("linkforge.l1.hit");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());
        assertTrue(counter("linkforge.l1.hit") > hit0, "a repeat read of a hot code must hit L1");

        // write-through: the new URL is visible immediately, via L1 or the row
        var updated = patchJson("/api/links/" + code,
                om.writeValueAsString(java.util.Map.of("originalUrl", "https://example.com/l1-v2")), token);
        assertEquals(200, updated.getStatusCode().value(), updated.getBody());
        var redirected = get("/r/" + code, null);
        assertEquals(302, redirected.getStatusCode().value());
        assertEquals("https://example.com/l1-v2", redirected.getHeaders().getLocation().toString());
    }

    @Test
    void localCacheDisabledFallsBackToRedisOnlyPath() throws Exception {
        properties.getL1().setEnabled(false);
        try {
            String token = registerForToken();
            String code = createLink(token, "https://example.com/l1-off");
            double hit0 = counter("linkforge.l1.hit");

            assertEquals(302, get("/r/" + code, null).getStatusCode().value());
            assertEquals(hit0, counter("linkforge.l1.hit"), "disabled L1 must not count hits");
        } finally {
            properties.getL1().setEnabled(true);
        }
    }
}
