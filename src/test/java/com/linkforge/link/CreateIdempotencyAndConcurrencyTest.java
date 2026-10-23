package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-p3:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=200",
        "linkforge.rate-limit.redirect-max=1000"
})
@ActiveProfiles("test")
class CreateIdempotencyAndConcurrencyTest extends AbstractIntegrationTest {

    @Test
    void idempotencyKeyReplaysSameLink() throws Exception {
        String token = registerForToken();
        String url = "https://example.com/idem-" + System.currentTimeMillis();
        String json = om.writeValueAsString(Map.of("originalUrl", url));

        var first = postJsonWithHeader("/api/links", json, token, "Idempotency-Key", "k-fixed");
        assertEquals(200, first.getStatusCode().value(), first.getBody());
        String code = body(first).path("data").path("code").asText();

        var replay = postJsonWithHeader("/api/links", json, token, "Idempotency-Key", "k-fixed");
        assertEquals(200, replay.getStatusCode().value(), replay.getBody());
        assertEquals(code, body(replay).path("data").path("code").asText(), "replay must return the same link");

        var list = get("/api/links?page=1&size=100", token);
        assertEquals(1, body(list).path("data").path("total").asLong(), "one physical row expected");
    }

    @Test
    void concurrentSameKeyCreatesSingleLink() throws Exception {
        String token = registerForToken();
        String json = om.writeValueAsString(Map.of("originalUrl",
                "https://example.com/conc-idem-" + System.currentTimeMillis()));

        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                calls.add(() -> {
                    var resp = postJsonWithHeader("/api/links", json, token, "Idempotency-Key", "k-conc");
                    assertEquals(200, resp.getStatusCode().value(), resp.getBody());
                    return body(resp).path("data").path("code").asText();
                });
            }
            Set<String> codes = new HashSet<>();
            for (Future<String> f : pool.invokeAll(calls)) {
                codes.add(f.get());
            }
            assertEquals(1, codes.size(), "all concurrent replays must see one code: " + codes);
        } finally {
            pool.shutdownNow();
        }

        var list = get("/api/links?page=1&size=100", token);
        assertEquals(1, body(list).path("data").path("total").asLong());
    }

    @Test
    void parallelCreatesYieldDistinctCodes() throws Exception {
        String token = registerForToken();
        String base = "https://example.com/par-" + System.currentTimeMillis() + "-";

        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<String>> calls = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                int idx = i;
                calls.add(() -> {
                    var resp = postJson("/api/links",
                            om.writeValueAsString(Map.of("originalUrl", base + idx)), token);
                    assertEquals(200, resp.getStatusCode().value(), resp.getBody());
                    return body(resp).path("data").path("code").asText();
                });
            }
            Set<String> codes = new HashSet<>();
            for (Future<String> f : pool.invokeAll(calls)) {
                codes.add(f.get());
            }
            assertEquals(30, codes.size(), "no duplicate codes allowed under parallel creates");
        } finally {
            pool.shutdownNow();
        }

        var list = get("/api/links?page=1&size=100", token);
        assertEquals(30, body(list).path("data").path("total").asLong());
    }

    @Test
    void withoutKeyCreatesSeparateLinks() throws Exception {
        String token = registerForToken();
        String url = "https://example.com/nokey-" + System.currentTimeMillis();
        assertNotEquals(createLink(token, url), createLink(token, url));
    }
}
