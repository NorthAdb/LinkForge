package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.link.support.UrlLocalCache;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C2 wave C (D-C2-15..20): L1 invalidate broadcast. Resolution-changing writes
 * (update/disable/enable/delete) publish {@code <code>|<origin>} on
 * {@code linkforge:l1:invalidate}; a remote instance's subscriber invalidates ONLY
 * its local Caffeine L1 (never the Redis L2, absent cache or Bloom), and the origin
 * skip keeps an instance's own broadcast from wiping the URL it just wrote through.
 * The 10s L1 TTL stays as the fallback.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-l1inv:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.redirect-max=1000",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class L1InvalidateIT extends AbstractIntegrationTest {

    public static final String CHANNEL = "linkforge:l1:invalidate";

    private static final CopyOnWriteArrayList<String> RECEIVED = new CopyOnWriteArrayList<>();
    private static RedisMessageListenerContainer peerListener;
    private static final String PEER_ORIGIN = "it-l1-peer";

    @Autowired
    private UrlLocalCache l1;
    @Autowired
    private StringRedisTemplate redis;

    @BeforeAll
    static void subscribePeerInstance(RedisConnectionFactory factory) {
        peerListener = new RedisMessageListenerContainer();
        peerListener.setConnectionFactory(factory);
        peerListener.addMessageListener((message, pattern) ->
                        RECEIVED.add(new String(message.getBody())), new ChannelTopic(CHANNEL));
        peerListener.afterPropertiesSet();
        peerListener.start();
    }

    @AfterAll
    static void stopPeerInstance() throws Exception {
        if (peerListener != null) {
            peerListener.stop();
            peerListener.destroy();
        }
    }

    /** Redirect once so resolveTarget backfills L1 and L2. */
    private String createAndWarm(String token) throws Exception {
        String code = createLink(token, "https://example.com/l1inv-" + System.currentTimeMillis());
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());
        return code;
    }

    @Test
    void remoteInvalidateMessageDropsOnlyTheLocalL1Entry() throws Exception {
        String token = registerForToken();
        String code = createAndWarm(token);
        assertNotNull(l1.getIfPresent(code), "the redirect must have backfilled the local L1");

        redis.convertAndSend(CHANNEL, code + "|" + PEER_ORIGIN);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertNull(l1.getIfPresent(code),
                        "a peer instance's invalidate must drop this instance's L1 slot"));
        String l2Key = "linkforge:code:" + code;
        assertTrue(Boolean.TRUE.equals(redis.hasKey(l2Key)),
                "D-C2-17: the subscriber must NOT delete the Redis L2 entry");
    }

    @Test
    void resolutionChangingWritesPublishOnceForTheOtherInstances() throws Exception {
        String token = registerForToken();
        String code = createAndWarm(token);

        var patched = patchJson("/api/links/" + code,
                om.writeValueAsString(java.util.Map.of("originalUrl", "https://example.com/l1inv-new-"
                        + System.currentTimeMillis())), token);
        assertEquals(200, patched.getStatusCode().value(), patched.getBody());
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertTrue(RECEIVED.stream().anyMatch(p -> p.startsWith(code + "|")),
                        "update must publish an invalidate for the other instances, peer saw " + RECEIVED));
        long afterUpdate = RECEIVED.stream().filter(p -> p.startsWith(code + "|")).count();

        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertTrue(RECEIVED.stream().filter(p -> p.startsWith(code + "|")).count() > afterUpdate,
                        "disable/enable must publish its own invalidate"));
        assertEquals(200, postJson("/api/links/" + code + "/disable", "{}", token).getStatusCode().value());

        assertEquals(200, delete("/api/links/" + code, token).getStatusCode().value());
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            long afterDelete = RECEIVED.stream().filter(p -> p.startsWith(code + "|")).count();
            assertTrue(afterDelete > afterUpdate + 1,
                    "delete must publish too (update + disable + delete = at least 3)");
        });
    }

    @Test
    void ownOriginDoesNotWipeTheJustWrittenThroughUrl() throws Exception {
        String token = registerForToken();
        String code = createAndWarm(token);

        var patched = patchJson("/api/links/" + code,
                om.writeValueAsString(java.util.Map.of("originalUrl", "https://example.com/l1own-"
                        + System.currentTimeMillis())), token);
        assertEquals(200, patched.getStatusCode().value(), patched.getBody());

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertTrue(RECEIVED.stream().anyMatch(p -> p.startsWith(code + "|")),
                        "the update broadcast must have arrived so the test knows the app origin"));
        String appOrigin = RECEIVED.stream().filter(p -> p.startsWith(code + "|")).findFirst()
                .orElseThrow().split("\\|", 2)[1];

        String freshUrl = l1.getIfPresent(code);
        assertNotNull(freshUrl, "the update must have written the fresh URL into the local L1");
        redis.convertAndSend(CHANNEL, code + "|" + appOrigin);
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        assertEquals(freshUrl, l1.getIfPresent(code),
                "C2-11: the origin skip must keep an instance's own broadcast from wiping "
                        + "the URL it just wrote through");
    }
}
