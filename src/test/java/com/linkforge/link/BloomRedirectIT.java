package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.config.LinkForgeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
