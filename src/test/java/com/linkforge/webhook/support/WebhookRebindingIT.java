package com.linkforge.webhook.support;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.config.LinkForgeProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-C4-1: the delivery hop must connect ONLY to addresses the guard resolved and
 * validated, never re-resolve DNS itself. Lives in the guard's package because the
 * stubbing seam is the package-private {@code resolveHost} the unit tests already use.
 * The public-IP spy stub is what lets an http://localhost endpoint pass the save-time
 * check without the allow-private escape hatch, so delivery-time resolution is fully
 * observable: whatever the spy returns is the only thing the sender may contact.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-rebind:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=1000",
        "linkforge.rate-limit.redirect-max=1000",
        "linkforge.mq.async-click-enabled=true",
        "linkforge.mq.relay-fixed-delay-ms=500",
        "linkforge.mq.relay-initial-delay-ms=1000",
        "spring.rabbitmq.listener.simple.auto-startup=true",
        "linkforge.webhook.allow-private-targets=true",
        "linkforge.webhook.relay-enabled=true",
        "linkforge.webhook.relay-fixed-delay-ms=500",
        "linkforge.webhook.relay-initial-delay-ms=1000",
        "linkforge.webhook.max-attempts=3",
        "linkforge.webhook.connect-timeout-ms=500",
        "linkforge.webhook.request-timeout-ms=1000",
        "linkforge.webhook.backoff-base-seconds=1",
        "linkforge.webhook.backoff-max-seconds=2",
        "spring.datasource.hikari.maximum-pool-size=4"
})
@ActiveProfiles("test")
// See WebhookDeliveryIT: relay-enabled contexts must not outlive their class or a
// cached context's drain thread settles another class's rows with the wrong config.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class WebhookRebindingIT extends AbstractIntegrationTest {

    private static final String PUBLIC_IP = "93.184.216.34"; // literal, no DNS
    private static final String PRIVATE_IP = "10.0.0.5";

    private static HttpServer stub;
    private static final List<String[]> RECEIVED = new CopyOnWriteArrayList<>();
    private static final AtomicInteger PORT = new AtomicInteger();
    private static final Map<String, ConcurrentLinkedQueue<Integer>> SCRIPTS = new ConcurrentHashMap<>();
    private static final Map<String, Long> SLEEP_MS = new ConcurrentHashMap<>();

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private LinkForgeProperties linkForgeProperties;

    @SpyBean
    private WebhookTargetGuard guard;

    @BeforeEach
    void resetGuardSpy() {
        Mockito.reset(guard);
    }

    @BeforeAll
    static void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // a handler may sleep past the client's request timeout, so it must never hold
        // the dispatch thread other requests (or retries) queue behind
        stub.setExecutor(Executors.newFixedThreadPool(8));
        PORT.set(stub.getAddress().getPort());
        stub.createContext("/hook", WebhookRebindingIT::handle);
        stub.start();
    }

    private static void handle(HttpExchange exchange) throws java.io.IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            byte[] body = exchange.getRequestBody().readAllBytes();
            RECEIVED.add(new String[]{path,
                    exchange.getRequestHeaders().getFirst(WebhookSigner.HEADER_EVENT_ID),
                    new String(body, StandardCharsets.UTF_8)});
            Long sleepMs = SLEEP_MS.get(path);
            if (sleepMs != null) {
                Thread.sleep(sleepMs);
            }
            java.util.Queue<Integer> script = SCRIPTS.get(path);
            Integer scripted = script == null ? null : script.poll();
            exchange.sendResponseHeaders(scripted != null ? scripted : 200, -1);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    @AfterAll
    static void stopStub() {
        if (stub != null) {
            stub.stop(0);
            if (stub.getExecutor() instanceof java.util.concurrent.ExecutorService pool) {
                pool.shutdownNow();
            }
        }
    }

    private void configureEndpoint(String token, String path) throws Exception {
        SCRIPTS.put(path, new ConcurrentLinkedQueue<>());
        var resp = rest.exchange("/api/webhooks/endpoint", org.springframework.http.HttpMethod.PUT,
                new org.springframework.http.HttpEntity<>(
                        om.writeValueAsString(Map.of("url", "http://localhost:" + PORT.get() + path, "enabled", true)),
                        jsonHeaders(token)), String.class);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
    }

    private static org.springframework.http.HttpHeaders jsonHeaders(String token) {
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    private Map<String, Object> latestDeliveryFor(String token) {
        Long tenantId = tenantIdOf(token);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, attempts, last_status_code, last_error, event_id "
                        + "FROM lf_webhook_delivery WHERE tenant_id = ? ORDER BY id DESC LIMIT 1", tenantId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long stubHitsFor(String eventId) {
        return RECEIVED.stream().filter(r -> eventId.equals(r[1])).count();
    }

    private Long tenantIdOf(String token) {
        try {
            String[] parts = token.split(Pattern.quote("."));
            byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
            return om.readTree(payload).path("tenantId").asLong();
        } catch (Exception ex) {
            throw new IllegalStateException("cannot decode tenant from the test jwt", ex);
        }
    }

    @Test
    void toctouDeliveryNeverReachesUnvalidatedIp() throws Exception {
        // the guard resolves public for every host, but a JDK-style client would still
        // re-resolve localhost to 127.0.0.1 and hit the stub — the TOCTOU this wave pins shut
        Mockito.doReturn(new InetAddress[]{InetAddress.getByName(PUBLIC_IP)})
                .when(guard).resolveHost(Mockito.anyString());
        try {
            String token = registerForToken();
            configureEndpoint(token, "/hook-toctou");
            String code = createLink(token, "https://example.com/hook-toctou");
            assertEquals(302, get("/r/" + code, null).getStatusCode().value());

            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                Map<String, Object> row = latestDeliveryFor(token);
                assertNotNull(row, "no delivery row yet");
                assertTrue("SENT".equals(row.get("status")) || "FAILED".equals(row.get("status")),
                        "delivery row never settled: " + row);
            });
            Map<String, Object> row = latestDeliveryFor(token);
            String eventId = String.valueOf(row.get("event_id"));
            assertEquals("FAILED", row.get("status"),
                    "a pinned send to the validated public IP must fail, never SENT: " + row);
            assertEquals(3, ((Number) row.get("attempts")).intValue(),
                    "max-attempts must be exhausted before FAILED: " + row);
            assertEquals(0, stubHitsFor(eventId),
                    "the stub (127.0.0.1) must never be contacted behind a public validated IP; RECEIVED=" + RECEIVED.size());
        } finally {
            Mockito.reset(guard);
        }
    }

    @Test
    void pinnedBlockWhenDeliveryResolvesPrivate() throws Exception {
        Mockito.doReturn(new InetAddress[]{InetAddress.getByName(PUBLIC_IP)})
                .when(guard).resolveHost(Mockito.anyString());
        try {
            String token = registerForToken();
            configureEndpoint(token, "/hook-rebind-block");
            String code = createLink(token, "https://example.com/hook-rebind-block");

            // delivery time: the same host now resolves private; the escape hatch must be
            // off for the IP checks to apply at all (flipped on the shared bean, restored below)
            Mockito.reset(guard);
            Mockito.doReturn(new InetAddress[]{InetAddress.getByName(PRIVATE_IP)})
                    .when(guard).resolveHost(Mockito.anyString());
            linkForgeProperties.getWebhook().setAllowPrivateTargets(false);
            double blocked0 = counter("linkforge.webhook.blocked");
            try {
                assertEquals(302, get("/r/" + code, null).getStatusCode().value());

                await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                    Map<String, Object> row = latestDeliveryFor(token);
                    assertNotNull(row, "no delivery row yet");
                    assertEquals("FAILED", row.get("status"), "row: " + row);
                    assertTrue(counter("linkforge.webhook.blocked") > blocked0,
                            "blocked meter must be incremented; row: " + row);
                });
                Map<String, Object> row = latestDeliveryFor(token);
                String eventId = String.valueOf(row.get("event_id"));
                assertEquals("blocked target", row.get("last_error"),
                        "the uniform no-echo reason, never the resolved address: " + row);
                assertEquals(0, stubHitsFor(eventId), "a blocked target must not be contacted");
            } finally {
                linkForgeProperties.getWebhook().setAllowPrivateTargets(true);
            }
        } finally {
            Mockito.reset(guard);
        }
    }

    @Test
    void requestTimeoutRetriesThenFails() throws Exception {
        String token = registerForToken();
        String path = "/hook-slow";
        SCRIPTS.put(path, new ConcurrentLinkedQueue<>());
        SLEEP_MS.put(path, 2000L); // 2x request-timeout-ms: every attempt must time out client-side
        configureEndpoint(token, path);
        String code = createLink(token, "https://example.com/hook-slow");
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());

        // C4-10: while the delivery is stuck in its retry window, redirects stay free —
        // this tenant has no endpoint, so the probe click never touches the delivery chain
        String probeToken = registerForToken();
        String probeCode = createLink(probeToken, "https://example.com/hook-slow-probe");
        assertEquals(302, get("/r/" + probeCode, null).getStatusCode().value(),
                "the 302 must never wait on the webhook");

        await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
            Map<String, Object> row = latestDeliveryFor(token);
            assertNotNull(row, "no delivery row yet");
            assertEquals("FAILED", row.get("status"), "row: " + row);
            assertEquals(3, ((Number) row.get("attempts")).intValue(), "row: " + row);
        });
        String eventId = String.valueOf(latestDeliveryFor(token).get("event_id"));
        assertTrue(stubHitsFor(eventId) >= 3,
                "every timed-out attempt must still reach the endpoint for event " + eventId
                        + ", stub saw " + RECEIVED.size() + " entries");
    }

    private double counter(String name) {
        var c = meterRegistry.find(name).counter();
        return c == null ? 0.0 : c.count();
    }
}
