package com.linkforge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * prod profile tightens exposure: health details behind auth, no swagger, no static
 * console. dev/test behavior is unchanged (covered by ObservabilityIT).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-prod:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=200",
        "linkforge.rate-limit.redirect-max=1000"
})
@ActiveProfiles({"test", "prod"})
class ProdProfileIT extends AbstractIntegrationTest {

    @Test
    void prodHidesHealthDetailsAndDocs() throws Exception {
        var health = get("/actuator/health", null);
        assertEquals(200, health.getStatusCode().value(), health.getBody());
        assertFalse(health.getBody().contains("components"), "prod must hide component details");

        // unauthenticated + denyAll -> our 401 entry point fires before the 403 denial
        assertEquals(401, get("/v3/api-docs", null).getStatusCode().value(), "prod must deny api-docs");
        assertEquals(401, get("/swagger-ui/index.html", null).getStatusCode().value(), "prod must deny swagger ui");
        assertEquals(404, get("/", null).getStatusCode().value(), "prod must not serve the static console");

        assertTrue(get("/api/health", null).getStatusCode().is2xxSuccessful());
        assertEquals(404, get("/r/nonexistent", null).getStatusCode().value(), "redirect path stays public");
    }
}
