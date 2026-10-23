package com.linkforge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-p3:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=200",
        "linkforge.rate-limit.redirect-max=1000"
})
@ActiveProfiles("test")
class HealthEndpointTest extends AbstractIntegrationTest {

    @Test
    void actuatorHealthExposesDbRedisRabbitComponents() throws Exception {
        var resp = get("/actuator/health", null);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        var components = body(resp).path("components");
        assertEquals("UP", components.path("db").path("status").asText(), resp.getBody());
        assertEquals("UP", components.path("redis").path("status").asText(), resp.getBody());
        assertEquals("UP", components.path("rabbit").path("status").asText(), resp.getBody());
        assertTrue(resp.getBody().contains("\"diskSpace\"") || components.size() >= 3,
                "component details must be visible: " + resp.getBody());
    }
}
