package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HEAD /r/{code} probes jumpability exactly like GET (302/404/410) but records no
 * click anywhere: no lf_click_log row, no UV, no daily aggregate.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-head:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=200",
        "linkforge.rate-limit.redirect-max=1000"
})
@ActiveProfiles("test")
class RedirectHeadIT extends AbstractIntegrationTest {

    /** Sync lf_click_log detail count: deterministic right after the redirect,
     * unlike stats totalClicks which reads the async lf_click_daily aggregate. */
    private long clickCount(String token, String code) throws Exception {
        var clicks = get("/api/links/" + code + "/clicks?size=1", token);
        assertEquals(200, clicks.getStatusCode().value(), clicks.getBody());
        return body(clicks).path("data").path("total").asLong();
    }

    @Test
    void headProbesValidLinkWithoutCountingClicks() throws Exception {
        String token = registerForToken();
        String target = "https://example.com/head-" + System.currentTimeMillis();
        String code = createLink(token, target);

        long before = clickCount(token, code);
        var probe = head("/r/" + code);
        assertEquals(302, probe.getStatusCode().value(), probe.getBody());
        assertEquals(target, probe.getHeaders().getLocation().toString());
        assertEquals(before, clickCount(token, code), "HEAD must not record a click");

        assertEquals(302, get("/r/" + code, null).getStatusCode().value());
        assertEquals(before + 1, clickCount(token, code), "GET after HEAD counts exactly one click");
    }

    @Test
    void headMatchesGetSemanticsForUnknownCode() {
        assertEquals(404, head("/r/" + unique("nosuch")).getStatusCode().value());
    }

    @Test
    void headMatchesGetSemanticsForDisabledLink() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/disabled-head");
        assertEquals(200, postJson("/api/links/" + code + "/disable", "{}", token).getStatusCode().value());

        assertEquals(404, head("/r/" + code).getStatusCode().value());
        assertEquals(404, get("/r/" + code, null).getStatusCode().value());
    }

    @Test
    void headMatchesGetSemanticsForExpiredLink() throws Exception {
        String token = registerForToken();
        var resp = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://example.com/expiring-head",
                "expireAt", LocalDateTime.now().plusSeconds(2).toString())), token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        String code = body(resp).path("data").path("code").asText();

        await().atMost(java.time.Duration.ofSeconds(6))
                .untilAsserted(() -> assertEquals(410, head("/r/" + code).getStatusCode().value()));
        assertEquals(410, get("/r/" + code, null).getStatusCode().value());
    }
}
