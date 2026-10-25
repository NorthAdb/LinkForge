package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-rl3:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=3",
        "linkforge.rate-limit.redirect-max=3"
})
@ActiveProfiles("test")
class RateLimitIT extends AbstractIntegrationTest {

    @Test
    void createBeyondPerUserLimitReturns429() throws Exception {
        String token = registerForToken();
        for (int i = 0; i < 3; i++) {
            var ok = postJson("/api/links",
                    om.writeValueAsString(java.util.Map.of("originalUrl", "https://example.com/rl-" + i)), token);
            assertEquals(200, ok.getStatusCode().value(), "create " + i + ": " + ok.getBody());
        }
        var limited = postJson("/api/links",
                om.writeValueAsString(java.util.Map.of("originalUrl", "https://example.com/rl-x")), token);
        assertEquals(429, limited.getStatusCode().value(), limited.getBody());
        assertEquals(429, body(limited).path("code").asInt());
        assertRetryAfter(limited, 60);
    }

    @Test
    void redirectBeyondPerIpLimitReturns429() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/rl-redirect");
        var spoofed = getWithHeader("/r/" + code, null, "X-Forwarded-For", "9.9.9.9");
        assertEquals(302, spoofed.getStatusCode().value(),
                "a spoofed XFF must not buy an extra window by default: " + spoofed.getBody());
        for (int i = 0; i < 2; i++) {
            var ok = get("/r/" + code, null);
            assertEquals(302, ok.getStatusCode().value(), "redirect " + i);
        }
        var limited = get("/r/" + code, null);
        assertEquals(429, limited.getStatusCode().value(), limited.getBody());
        assertEquals(429, body(limited).path("code").asInt());
        assertRetryAfter(limited, 60);
    }

    private static void assertRetryAfter(ResponseEntity<String> resp, int windowSeconds) {
        String raw = resp.getHeaders().getFirst("Retry-After");
        assertNotNull(raw, "429 must carry Retry-After");
        int seconds = Integer.parseInt(raw.trim());
        assertTrue(seconds >= 1 && seconds <= windowSeconds,
                "Retry-After " + seconds + " outside [1, " + windowSeconds + "]");
    }
}
