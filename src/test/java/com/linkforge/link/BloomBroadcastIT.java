package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.link.support.CodeBloomFilter;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C2 wave A (D-C2-3..8): multi-instance Bloom alignment over Redis pub/sub. The
 * filter stays process-local Guava; a put on ANOTHER instance arrives as a channel
 * message and must teach THIS filter without going through doCreate; a create here
 * must publish the code for the other instances. Payload contract: {@code <code>|<origin>}
 * on channel {@code linkforge:bloom:put}; the local filter re-puts its own messages
 * (idempotent), the L1 channel is where origin skipping matters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-bloomcast:",
        "linkforge.rate-limit.register-max=1000",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
class BloomBroadcastIT extends AbstractIntegrationTest {

    public static final String CHANNEL = "linkforge:bloom:put";

    private static final CopyOnWriteArrayList<String> RECEIVED = new CopyOnWriteArrayList<>();
    private static RedisMessageListenerContainer peerListener;
    private static final String PEER_ORIGIN = "it-peer-" + UUID.randomUUID();

    @Autowired
    private CodeBloomFilter codeBloomFilter;

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

    @Test
    void remotePutMessageTeachesThisInstanceWithoutAnyCreate() {
        String code = "rb" + UUID.randomUUID().toString().replace("-", "").substring(0, 5);
        assertFalse(codeBloomFilter.mightContain(code),
                "an unknown code must start negative (filter is loaded and empty of it)");

        redis.convertAndSend(CHANNEL, code + "|" + PEER_ORIGIN);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertTrue(codeBloomFilter.mightContain(code),
                        "a peer instance's put broadcast must teach this process-local filter"));
    }

    @Test
    void createPublishesTheCodeForTheOtherInstances() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/bloomcast-" + System.currentTimeMillis());

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertTrue(RECEIVED.stream().anyMatch(p -> p.startsWith(code + "|")),
                        "create must broadcast the code on " + CHANNEL + ", peer saw " + RECEIVED));
        String payload = RECEIVED.stream().filter(p -> p.startsWith(code + "|")).findFirst().orElseThrow();
        assertEquals(code, payload.split("\\|", 2)[0], "payload must carry the bare code first");

        assertEquals(1, RECEIVED.stream().filter(p -> p.startsWith(code + "|")).count(),
                "the broadcast must happen exactly once per create (not from the delayed clear)");
        assertTrue(codeBloomFilter.mightContain(code), "the local put still happens on the creating instance");
    }
}
