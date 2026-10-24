package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * With trust-xff=true (behind a trusted proxy), each forwarded IP gets its own
 * fixed window. The same four requests against the default profile share one
 * window and the fourth would be 429 (covered by RateLimitTest).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-xff:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.redirect-max=3",
        "linkforge.rate-limit.trust-xff=true"
})
@ActiveProfiles("test")
class XffTrustIT extends AbstractIntegrationTest {

    @Test
    void distinctForwardedIpsGetIndependentWindows() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/xff-" + System.currentTimeMillis());
        for (int i = 0; i < 4; i++) {
            var resp = getWithHeader("/r/" + code, null, "X-Forwarded-For", "10.0.0." + i);
            assertEquals(302, resp.getStatusCode().value(), "xff " + i + ": " + resp.getBody());
        }
    }
}
