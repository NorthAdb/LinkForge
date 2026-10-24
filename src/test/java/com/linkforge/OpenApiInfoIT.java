package com.linkforge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * dev/test keeps api-docs public; the info block must carry the real title and the
 * pom version from build-info, not springdoc's defaults. prod denial is covered by
 * ProdProfileIT.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-openapi:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=200",
        "linkforge.rate-limit.redirect-max=1000"
})
@ActiveProfiles("test")
class OpenApiInfoIT extends AbstractIntegrationTest {

    @Test
    void apiDocsCarryRealTitleAndPomVersion() throws Exception {
        var resp = get("/v3/api-docs", null);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        assertEquals("LinkForge", body(resp).path("info").path("title").asText());
        assertEquals("1.0.0", body(resp).path("info").path("version").asText());
    }
}
