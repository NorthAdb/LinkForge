package com.linkforge.link;

import com.fasterxml.jackson.databind.JsonNode;
import com.linkforge.AbstractIntegrationTest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C3 A/B weighted target split (D-C3-1..13, coordinator decisions 1-10): one code
 * carries 0..N weighted variants; the redirect draws one after every chain-level
 * gate (password/phishing stay on the link row), stats bucket clicks by the drawn
 * key, and updates replace the whole variant set under the existing L1 broadcast.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-ab1:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=1000",
        "linkforge.rate-limit.redirect-max=1000",
        "spring.datasource.hikari.maximum-pool-size=4",
        "linkforge.mq.async-click-enabled=true",
        "linkforge.mq.relay-fixed-delay-ms=500",
        "linkforge.mq.relay-initial-delay-ms=1000",
        "spring.rabbitmq.listener.simple.auto-startup=true"
})
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AbSplitIT extends AbstractIntegrationTest {

    private static final String L1_CHANNEL = "linkforge:l1:invalidate";

    private final CopyOnWriteArrayList<String> received = new CopyOnWriteArrayList<>();
    private RedisMessageListenerContainer peerListener;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RedisConnectionFactory connectionFactory;

    /** Stands in for another instance so the test can observe the app's broadcasts. */
    @BeforeAll
    void subscribePeerInstance() {
        peerListener = new RedisMessageListenerContainer();
        peerListener.setConnectionFactory(connectionFactory);
        peerListener.addMessageListener((message, pattern) ->
                received.add(new String(message.getBody())), new ChannelTopic(L1_CHANNEL));
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

    private ResponseEntity<String> postForm(String uri, String form, String cookie) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        if (cookie != null) {
            headers.set(HttpHeaders.COOKIE, cookie);
        }
        return rest.exchange(uri, HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
    }

    /** Creates a 50/50 A/B link whose originalUrl is a third, distinct URL. */
    private String createAbLink(String token, String urlMain, String urlA, String urlB) throws Exception {
        String json = om.writeValueAsString(Map.of(
                "originalUrl", urlMain,
                "variants", List.of(
                        Map.of("key", "A", "url", urlA, "weight", 50),
                        Map.of("key", "B", "url", urlB, "weight", 50))));
        ResponseEntity<String> resp = postJson("/api/links", json, token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        return om.readTree(resp.getBody()).path("data").path("code").asText();
    }

    @Test
    void variantTableExists() {
        List<String> tables = jdbc.queryForList("SHOW TABLES LIKE 'lf_link_variant'", String.class);
        assertEquals(1, tables.size(), "V10 must create lf_link_variant");
    }

    @Test
    void plainLinkRedirectStaysOnOriginalUrl() throws Exception {
        String token = registerForToken();
        String originalUrl = "https://plain.example.com/solo-" + unique("");
        String code = createLink(token, originalUrl);

        ResponseEntity<String> resp = get("/r/" + code, null);
        assertEquals(302, resp.getStatusCode().value(), resp.getBody());
        assertEquals(originalUrl, String.valueOf(resp.getHeaders().getLocation()),
                "C3-3: a link without variants keeps the exact pre-C3 behavior");
    }

    @Test
    void createWithVariantsExposesThemInDetail() throws Exception {
        String token = registerForToken();
        String code = createAbLink(token, "https://ab-main.example.com/d-" + unique(""),
                "https://ab-a.example.com/d-" + unique(""), "https://ab-b.example.com/d-" + unique(""));

        ResponseEntity<String> detail = get("/api/links/" + code, token);
        assertEquals(200, detail.getStatusCode().value(), detail.getBody());
        JsonNode variants = body(detail).path("data").path("variants");
        assertTrue(variants.isArray() && variants.size() == 2,
                "detail must expose the two submitted variants: " + detail.getBody());
        Set<String> keys = new HashSet<>();
        for (JsonNode variant : variants) {
            assertTrue(variant.hasNonNull("key") && variant.hasNonNull("url") && variant.hasNonNull("weight"),
                    "each variant view carries key/url/weight: " + variant);
            keys.add(variant.path("key").asText());
        }
        assertEquals(Set.of("A", "B"), keys);
    }

    @Test
    void weightedRedirectDrawsBothVariants() throws Exception {
        String token = registerForToken();
        String urlA = "https://ab-a.example.com/w-" + unique("");
        String urlB = "https://ab-b.example.com/w-" + unique("");
        String code = createAbLink(token, "https://ab-main.example.com/w-" + unique(""), urlA, urlB);

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 60; i++) {
            ResponseEntity<String> resp = get("/r/" + code, null);
            assertEquals(302, resp.getStatusCode().value(), "redirect " + i + ": " + resp.getBody());
            String location = String.valueOf(resp.getHeaders().getLocation());
            assertTrue(urlA.equals(location) || urlB.equals(location),
                    "every Location must be a variant target, got " + location);
            seen.add(location);
        }
        // 60 fair draws miss one side with p ~= 1.7e-18.
        assertEquals(Set.of(urlA, urlB), seen, "50/50 over 60 draws must hit both variants");
    }

    @Test
    void passwordChainDrawsVariantAfterUnlock() throws Exception {
        String token = registerForToken();
        String urlA = "https://ab-a.example.com/pw-" + unique("");
        String urlB = "https://ab-b.example.com/pw-" + unique("");
        String json = om.writeValueAsString(Map.of(
                "originalUrl", urlA,
                "accessPassword", "s3cret-pass",
                "variants", List.of(Map.of("key", "B", "url", urlB, "weight", 100))));
        ResponseEntity<String> resp = postJson("/api/links", json, token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        String code = om.readTree(resp.getBody()).path("data").path("code").asText();

        ResponseEntity<String> locked = get("/r/" + code, null);
        assertEquals(200, locked.getStatusCode().value(), "a locked GET must render the unlock page");
        assertTrue(locked.getBody().contains("password"));

        ResponseEntity<String> unlock = postForm("/r/" + code + "/unlock", "password=s3cret-pass", null);
        assertEquals(303, unlock.getStatusCode().value(), unlock.getBody());
        String cookie = unlock.getHeaders().getFirst(HttpHeaders.SET_COOKIE).split(";", 2)[0];

        ResponseEntity<String> jump = getWithHeader("/r/" + code, null, HttpHeaders.COOKIE, cookie);
        assertEquals(302, jump.getStatusCode().value(), "the unlocked cookie buys a real 302");
        assertEquals(urlB, String.valueOf(jump.getHeaders().getLocation()),
                "the post-unlock jump must draw the variant target");
    }

    @Test
    void patchReplacesVariantsAsAWhole() throws Exception {
        String token = registerForToken();
        String code = createAbLink(token, "https://ab-main.example.com/rep-" + unique(""),
                "https://ab-a.example.com/rep-" + unique(""), "https://ab-b.example.com/rep-" + unique(""));

        String urlX = "https://ab-x.example.com/rep-" + unique("");
        ResponseEntity<String> patched = patchJson("/api/links/" + code, om.writeValueAsString(Map.of(
                "variants", List.of(Map.of("key", "X", "url", urlX, "weight", 9)))), token);
        assertEquals(200, patched.getStatusCode().value(), patched.getBody());
        JsonNode afterX = body(get("/api/links/" + code, token)).path("data").path("variants");
        assertEquals(1, afterX.size(), "the submitted set must fully replace the old one: " + afterX);
        assertEquals("X", afterX.get(0).path("key").asText());
        assertEquals(9, afterX.get(0).path("weight").asInt());

        ResponseEntity<String> cleared = patchJson("/api/links/" + code,
                om.writeValueAsString(Map.of("variants", List.of())), token);
        assertEquals(200, cleared.getStatusCode().value(), cleared.getBody());
        JsonNode afterClear = body(get("/api/links/" + code, token)).path("data").path("variants");
        assertTrue(afterClear.isMissingNode() || afterClear.isNull(),
                "an empty set clears back to single-target");
    }

    @Test
    void variantClicksLandInTheVariantDimension() throws Exception {
        String token = registerForToken();
        String code = createAbLink(token, "https://ab-main.example.com/st-" + unique(""),
                "https://ab-a.example.com/st-" + unique(""), "https://ab-b.example.com/st-" + unique(""));
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());

        ResponseEntity<String> geo = get("/api/links/" + code + "/stats/breakdown?dim=geo", token);
        assertEquals(400, geo.getStatusCode().value(), "unknown dim stays a 400");

        String tok = token;
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            ResponseEntity<String> resp = get("/api/links/" + code + "/stats/breakdown?dim=variant", tok);
            assertEquals(200, resp.getStatusCode().value(),
                    "the variant dim must be allowed: " + resp.getBody());
            boolean drew = false;
            for (JsonNode row : body(resp).path("data")) {
                String value = row.path("dimValue").asText();
                drew = drew || value.equals("A") || value.equals("B");
            }
            assertTrue(drew, "the redirect click must carry its variant key");
        });
    }

    @Test
    void patchingVariantsPublishesL1Invalidate() throws Exception {
        String token = registerForToken();
        String code = createAbLink(token, "https://ab-main.example.com/l1-" + unique(""),
                "https://ab-a.example.com/l1-" + unique(""), "https://ab-b.example.com/l1-" + unique(""));

        ResponseEntity<String> patched = patchJson("/api/links/" + code, om.writeValueAsString(Map.of(
                "variants", List.of(Map.of("key", "X", "url", "https://ab-x.example.com/l1-" + unique(""),
                        "weight", 9)))), token);
        assertEquals(200, patched.getStatusCode().value(), patched.getBody());
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertTrue(received.stream().anyMatch(p -> p.startsWith(code + "|")),
                        "a variants-changing update must publish the usual L1 invalidate (C3-9), peer saw "
                                + received));
    }
}
