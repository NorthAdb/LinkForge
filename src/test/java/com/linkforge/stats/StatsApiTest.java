package com.linkforge.stats;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.stats.mq.ClickEvent;
import com.linkforge.stats.service.ClickStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-stats:",
        "linkforge.rate-limit.register-max=1000"
})
@ActiveProfiles("test")
class StatsApiTest extends AbstractIntegrationTest {

    @Autowired
    private ClickStatsService clickStatsService;

    @Test
    void statsVisibleOnlyToOwner() throws Exception {
        String tokenA = registerForToken();
        String tokenB = registerForToken();
        String code = createLink(tokenA, "https://example.com/stats-" + System.currentTimeMillis());
        var detail = body(get("/api/links/" + code, tokenA)).path("data");
        long linkId = detail.path("id").asLong();
        long tenantId = 1L;
        String stamp = String.valueOf(System.currentTimeMillis());

        clickStatsService.consume(new ClickEvent("st-a" + stamp, tenantId, linkId, code,
                null, null, null, null, LocalDateTime.now()));
        clickStatsService.consume(new ClickEvent("st-b" + stamp, tenantId, linkId, code,
                null, null, null, null, LocalDateTime.now()));
        clickStatsService.consume(new ClickEvent("st-c" + stamp, tenantId, linkId, code,
                null, null, null, null, LocalDateTime.now().minusDays(1)));

        var own = get("/api/links/" + code + "/stats", tokenA);
        assertEquals(200, own.getStatusCode().value(), own.getBody());
        var data = body(own).path("data");
        assertEquals(3, data.path("totalClicks").asLong());
        assertEquals(7, data.path("daily").size());

        var windowed = get("/api/links/" + code + "/stats?days=2", tokenA);
        var windowData = body(windowed).path("data");
        assertEquals(3, windowData.path("totalClicks").asLong());
        assertEquals(2, windowData.path("daily").size());
        assertEquals(1, windowData.path("daily").get(0).path("clicks").asLong());
        assertEquals(2, windowData.path("daily").get(1).path("clicks").asLong());

        var foreign = get("/api/links/" + code + "/stats", tokenB);
        assertEquals(404, foreign.getStatusCode().value(), "stats must be owner-only: " + foreign.getBody());
    }
}
