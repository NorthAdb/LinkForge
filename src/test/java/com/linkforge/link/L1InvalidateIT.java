package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.link.support.UrlLocalCache;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class L1InvalidateIT extends AbstractIntegrationTest {

    public static final String CHANNEL = "linkforge:l1:invalidate";

    private final CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
    private RedisMessageListenerContainer peerListener;
    private final String peerOrigin = "it-l1-peer";

    @Autowired
    private UrlLocalCache l1;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private RedisConnectionFactory connectionFactory;

    @BeforeAll
    void subscribePeerInstance() {
        peerListener = new RedisMessageListenerContainer();
        peerListener.setConnectionFactory(connectionFactory);
        peerListener.addMessageListener((message, pattern) ->
                received.add(new String(message.getBody())), new ChannelTopic(CHANNEL));
        peerListener.afterPropertiesSet();
        peerListener.start();
    }

    @AfterAll
    void stopPeerInstance() throws Exception {
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

        redis.convertAndSend(CHANNEL, code + "|" + peerOrigin);

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
                assertTrue(received.stream().anyMatch(p -> p.startsWith(code + "|")),
                        "update must publish an invalidate for the other instances, peer saw " + received));
        long afterUpdate = received.stream().filter(p -> p.startsWith(code + "|")).count();

        assertEquals(200, postJson("/api/links/" + code + "/disable", "{}", token).getStatusCode().value());
        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertTrue(received.stream().filter(p -> p.startsWith(code + "|")).count() > afterUpdate,
                        "disable must publish its own invalidate"));
        long afterDisable = received.stream().filter(p -> p.startsWith(code + "|")).count();

        assertEquals(200, delete("/api/links/" + code, token).getStatusCode().value());
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertTrue(received.stream().filter(p -> p.startsWith(code + "|")).count() > afterDisable,
                        "delete must publish too (update + disable + delete = at least 3)"));
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
                assertTrue(received.stream().anyMatch(p -> p.startsWith(code + "|")),
                        "the update broadcast must have arrived so the test knows the app origin"));
        String appOrigin = received.stream().filter(p -> p.startsWith(code + "|")).findFirst()
                .orElseThrow().split("\\|", 2)[1];

        // The update's own delayed stale clear (~1s) wipes the local slot BY DESIGN;
        // wait it out and re-warm so the only thing under test is the origin skip.
        try {
            Thread.sleep(1600);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());
        String freshUrl = l1.getIfPresent(code);
        assertNotNull(freshUrl, "the re-warm must have backfilled the local L1");

        redis.convertAndSend(CHANNEL, code + "|" + appOrigin);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        assertEquals(freshUrl, l1.getIfPresent(code),
                "C2-11: the origin skip must keep an instance's own broadcast from wiping "
                        + "the URL it just wrote through");
    }
}
