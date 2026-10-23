package com.linkforge.link;

import com.linkforge.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-iso:",
        "linkforge.rate-limit.register-max=1000"
})
@ActiveProfiles("test")
class TenantIsolationTest extends AbstractIntegrationTest {

    @Test
    void crossUserAccessIsRejectedEverywhere() throws Exception {
        String tokenA = registerForToken();
        String tokenB = registerForToken();
        String code = createLink(tokenA, "https://example.com/iso-" + System.currentTimeMillis());

        var detail = get("/api/links/" + code, tokenB);
        assertEquals(404, detail.getStatusCode().value(), "B reading A's detail: " + detail.getBody());

        var patched = patchJson("/api/links/" + code,
                om.writeValueAsString(Map.of("originalUrl", "https://evil.example.com")), tokenB);
        assertEquals(404, patched.getStatusCode().value(), "B updating A's link: " + patched.getBody());

        var disabled = postJson("/api/links/" + code + "/disable", "{}", tokenB);
        assertEquals(404, disabled.getStatusCode().value(), "B disabling A's link: " + disabled.getBody());

        var listB = get("/api/links?page=1&size=100", tokenB);
        assertEquals(200, listB.getStatusCode().value(), listB.getBody());
        var records = body(listB).path("data").path("records");
        for (int i = 0; i < records.size(); i++) {
            assertFalse(code.equals(records.get(i).path("code").asText()),
                    "B's list must not contain A's code");
        }

        var stillLive = get("/r/" + code, null);
        assertEquals(302, stillLive.getStatusCode().value(), "B's failed tampering must not affect A's redirect");

        var ownerDetail = get("/api/links/" + code, tokenA);
        assertEquals(200, ownerDetail.getStatusCode().value(), ownerDetail.getBody());
        assertTrue(body(ownerDetail).path("data").path("originalUrl").asText().contains("example.com"));
    }
}
