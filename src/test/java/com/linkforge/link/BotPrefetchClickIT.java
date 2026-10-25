package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * B2: prefetch headers and named preview bots get the exact same 302 as any GET,
 * but nothing downstream records them — no lf_click_log row, no outbox event, no
 * redirects counter. curl stays counted (the skip list is narrow on purpose).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-head:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=200",
        "linkforge.rate-limit.redirect-max=1000"
})
@ActiveProfiles("test")
class BotPrefetchClickIT extends AbstractIntegrationTest {

    /** Sync lf_click_log detail count: deterministic right after the redirect. */
    private long clickCount(String token, String code) throws Exception {
        var clicks = get("/api/links/" + code + "/clicks?size=1", token);
        assertEquals(200, clicks.getStatusCode().value(), clicks.getBody());
        return body(clicks).path("data").path("total").asLong();
    }

    private double metricValue(String token, String name, String tag) throws Exception {
        String uri = "/actuator/metrics/" + name + (tag == null ? "" : "?" + tag);
        ResponseEntity<String> m = get(uri, token);
        assertEquals(200, m.getStatusCode().value(), "metric " + name + " must exist: " + m.getBody());
        return body(m).path("measurements").path(0).path("value").asDouble();
    }

    @Test
    void prefetchAndPreviewBotJumpWithoutCounting() throws Exception {
        String token = registerForToken();
        String target = "https://example.com/b2-" + System.currentTimeMillis();
        String code = createLink(token, target);

        long before = clickCount(token, code);
        double redirectsBefore = metricValue(token, "linkforge.redirects", null);

        var prefetch = getWithHeader("/r/" + code, null, "Sec-Purpose", "prefetch");
        assertEquals(302, prefetch.getStatusCode().value(), prefetch.getBody());
        var preview = getWithHeader("/r/" + code, null, "User-Agent",
                "Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)");
        assertEquals(302, preview.getStatusCode().value(), preview.getBody());

        assertEquals(before, clickCount(token, code),
                "prefetch and preview-bot GETs return 302 but must not record a click");
        assertEquals(redirectsBefore, metricValue(token, "linkforge.redirects", null),
                "skipped jumps must not bump linkforge.redirects");
        double skipped = metricValue(token, "linkforge.click.skipped", null);
        assertEquals(2.0, skipped, "one prefetch + one preview_bot skip expected: " + skipped);

        var normal = get("/r/" + code, null);
        assertEquals(302, normal.getStatusCode().value());
        assertEquals(prefetch.getHeaders().getLocation(), normal.getHeaders().getLocation(),
                "a skipped request must see the exact same Location as a counted one");
        assertEquals(before + 1, clickCount(token, code), "the real navigation counts");

        var curl = getWithHeader("/r/" + code, null, "User-Agent", "curl/8.4.0");
        assertEquals(302, curl.getStatusCode().value());
        assertEquals(before + 2, clickCount(token, code),
                "curl is not a preview bot — it must stay counted");
        assertEquals(1.0, metricValue(token, "linkforge.click.skipped", "tag=reason:prefetch"),
                "tag values are bounded: prefetch");
        assertEquals(1.0, metricValue(token, "linkforge.click.skipped", "tag=reason:preview_bot"),
                "tag values are bounded: preview_bot");
    }
}
