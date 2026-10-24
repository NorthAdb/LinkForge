package com.linkforge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-obs:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=100",
        "linkforge.rate-limit.redirect-max=2"
})
@ActiveProfiles("test")
class ObservabilityIT extends AbstractIntegrationTest {

    private double metricValue(String metric, String token) throws Exception {
        var resp = get("/actuator/metrics/" + metric, token);
        assertEquals(200, resp.getStatusCode().value(), metric + ": " + resp.getBody());
        return body(resp).path("measurements").get(0).path("value").asDouble();
    }

    @Test
    void businessCountersAndRejectionsAreExposed() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/obs-" + System.currentTimeMillis());

        assertEquals(302, get("/r/" + code, null).getStatusCode().value());
        assertEquals(302, get("/r/" + code, null).getStatusCode().value());
        assertTrue(metricValue("linkforge.links.created", token) >= 1, "links.created counter");
        assertTrue(metricValue("linkforge.redirects", token) >= 2, "redirects counter");

        assertEquals(429, get("/r/" + code, null).getStatusCode().value());
        var rejected = get("/actuator/metrics/linkforge.ratelimit.rejected?tag=entry:redirect", token);
        assertEquals(200, rejected.getStatusCode().value(), rejected.getBody());
        assertTrue(body(rejected).path("measurements").get(0).path("value").asDouble() >= 1,
                "ratelimit.rejected{entry=redirect}: " + rejected.getBody());
    }

    @Test
    void apiDocsSwaggerUiAndStaticPageAreServed() throws Exception {
        var docs = get("/v3/api-docs", null);
        assertEquals(200, docs.getStatusCode().value(), docs.getBody());
        assertTrue(docs.getBody().contains("/api/links"), "api-docs must list the links endpoints");

        var ui = get("/swagger-ui/index.html", null);
        assertEquals(200, ui.getStatusCode().value(), "swagger ui must be reachable");

        var page = get("/", null);
        assertEquals(200, page.getStatusCode().value());
        assertTrue(page.getBody().contains("LinkForge"), "static console page must be served");
    }
}
