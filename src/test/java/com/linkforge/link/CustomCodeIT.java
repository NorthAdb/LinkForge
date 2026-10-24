package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-p3:",
        "linkforge.rate-limit.register-max=1000",
        "linkforge.rate-limit.create-max=200",
        "linkforge.rate-limit.redirect-max=1000"
})
@ActiveProfiles("test")
class CustomCodeIT extends AbstractIntegrationTest {

    private String createWithCode(String token, String customCode) throws Exception {
        var resp = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://example.com/custom-" + System.currentTimeMillis(),
                "customCode", customCode)), token);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        return body(resp).path("data").path("code").asText();
    }

    @Test
    void customCodeIsUsedAndRedirects() throws Exception {
        String token = registerForToken();
        String code = createWithCode(token, "brand" + System.currentTimeMillis() % 100000);
        var redirected = get("/r/" + code, null);
        assertEquals(302, redirected.getStatusCode().value(), redirected.getBody());
    }

    @Test
    void duplicateCustomCodeRejectedAcrossUsers() throws Exception {
        String code = "dup" + System.currentTimeMillis() % 1000000;
        String tokenA = registerForToken();
        createWithCode(tokenA, code);

        var sameUser = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://example.com/x", "customCode", code)), tokenA);
        assertEquals(409, sameUser.getStatusCode().value(), sameUser.getBody());

        String tokenB = registerForToken();
        var otherUser = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://example.com/x", "customCode", code)), tokenB);
        assertEquals(409, otherUser.getStatusCode().value(), otherUser.getBody());
        assertEquals(409, body(otherUser).path("code").asInt());
    }

    @Test
    void invalidCustomCodesRejected() throws Exception {
        String token = registerForToken();
        for (String bad : new String[]{"ab!", "abc", "api", "actuator", "admin", "ADMIN", "way-too-long-code-12345"}) {
            var resp = postJson("/api/links", om.writeValueAsString(Map.of(
                    "originalUrl", "https://example.com/x", "customCode", bad)), token);
            assertEquals(400, resp.getStatusCode().value(), "customCode '" + bad + "': " + resp.getBody());
        }
    }

    @Test
    void deletedCodeStaysReserved() throws Exception {
        String code = "gone" + System.currentTimeMillis() % 1000000;
        String token = registerForToken();
        createWithCode(token, code);
        assertEquals(200, delete("/api/links/" + code, token).getStatusCode().value());

        var recreated = postJson("/api/links", om.writeValueAsString(Map.of(
                "originalUrl", "https://example.com/x", "customCode", code)), token);
        assertEquals(409, recreated.getStatusCode().value(),
                "uk_code keeps deleted codes reserved: " + recreated.getBody());
    }

    @Test
    void crossUserLifecycleOnCustomCodeStillIsolated() throws Exception {
        String tokenA = registerForToken();
        String tokenB = registerForToken();
        String code = createWithCode(tokenA, "iso" + System.currentTimeMillis() % 1000000);

        assertEquals(404, delete("/api/links/" + code, tokenB).getStatusCode().value());
        assertEquals(404, postJson("/api/links/" + code + "/disable", "{}", tokenB).getStatusCode().value());
        assertEquals(200, get("/api/links/" + code, tokenA).getStatusCode().value());
    }
}
