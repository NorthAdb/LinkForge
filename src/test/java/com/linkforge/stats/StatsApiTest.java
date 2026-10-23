package com.linkforge.stats;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.stats.entity.ClickDaily;
import com.linkforge.stats.entity.ConsumedEvent;
import com.linkforge.stats.mapper.ClickDailyMapper;
import com.linkforge.stats.mapper.ConsumedEventMapper;
import com.linkforge.stats.mq.ClickEvent;
import com.linkforge.stats.service.ClickStatsService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-stats:",
        "linkforge.rate-limit.register-max=1000"
})
@ActiveProfiles("test")
class StatsApiTest extends AbstractIntegrationTest {

    @Autowired
    private ClickStatsService clickStatsService;

    @Autowired
    private ClickDailyMapper clickDailyMapper;

    @Autowired
    private ConsumedEventMapper consumedEventMapper;

    private final List<String> syntheticEventIds = new ArrayList<>();
    private final List<String> syntheticCodes = new ArrayList<>();

    @AfterEach
    void cleanSyntheticStats() {
        if (!syntheticEventIds.isEmpty()) {
            consumedEventMapper.delete(new LambdaQueryWrapper<ConsumedEvent>()
                    .in(ConsumedEvent::getEventId, syntheticEventIds));
        }
        if (!syntheticCodes.isEmpty()) {
            clickDailyMapper.delete(new LambdaQueryWrapper<ClickDaily>()
                    .in(ClickDaily::getCode, syntheticCodes));
        }
    }

    @Test
    void statsVisibleOnlyToOwner() throws Exception {
        String tokenA = registerForToken();
        String tokenB = registerForToken();
        String code = createLink(tokenA, "https://example.com/stats-" + System.currentTimeMillis());
        syntheticCodes.add(code);
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
        syntheticEventIds.add("st-a" + stamp);
        syntheticEventIds.add("st-b" + stamp);
        syntheticEventIds.add("st-c" + stamp);

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
