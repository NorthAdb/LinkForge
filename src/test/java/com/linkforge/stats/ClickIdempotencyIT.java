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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-idem:",
        "linkforge.rate-limit.register-max=1000"
})
@ActiveProfiles("test")
class ClickIdempotencyIT extends AbstractIntegrationTest {

    @Autowired
    private ClickStatsService clickStatsService;

    @Autowired
    private ClickDailyMapper clickDailyMapper;

    @Autowired
    private ConsumedEventMapper consumedEventMapper;

    @Autowired
    private com.linkforge.stats.mapper.ClickLogMapper clickLogMapper;

    @Autowired
    private com.linkforge.stats.mapper.ClickDimDailyMapper clickDimDailyMapper;

    private final List<String> syntheticEventIds = new ArrayList<>();
    private final List<String> syntheticCodes = new ArrayList<>();
    private final List<Long> syntheticLinkIds = new ArrayList<>();

    @AfterEach
    void cleanSyntheticStats() {
        if (!syntheticEventIds.isEmpty()) {
            consumedEventMapper.delete(new LambdaQueryWrapper<ConsumedEvent>()
                    .in(ConsumedEvent::getEventId, syntheticEventIds));
        }
        if (!syntheticCodes.isEmpty()) {
            clickDailyMapper.delete(new LambdaQueryWrapper<ClickDaily>()
                    .in(ClickDaily::getCode, syntheticCodes));
            // consume() backfills lf_click_log for these synthetic UUID events (A7)
            clickLogMapper.delete(new LambdaQueryWrapper<com.linkforge.stats.entity.ClickLog>()
                    .in(com.linkforge.stats.entity.ClickLog::getCode, syntheticCodes));
        }
        if (!syntheticLinkIds.isEmpty()) {
            // consume() also writes dimension buckets now (A8)
            clickDimDailyMapper.delete(new LambdaQueryWrapper<com.linkforge.stats.entity.ClickDimDaily>()
                    .in(com.linkforge.stats.entity.ClickDimDaily::getLinkId, syntheticLinkIds));
        }
    }

    @Test
    void sameEventIdCountsOnceAcrossReplays() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/idem-" + System.currentTimeMillis());
        long linkId = linkIdOf(code, token);

        ClickEvent event = new ClickEvent("it-" + System.currentTimeMillis(), 1L, linkId, code,
                null, "127.0.0.1", "it-ua", null, LocalDateTime.now());
        syntheticEventIds.add(event.getEventId());
        syntheticCodes.add(code);
        syntheticLinkIds.add(linkId);

        assertTrue(clickStatsService.consume(event), "first delivery must count");
        assertFalse(clickStatsService.consume(event), "replay must be skipped");
        assertFalse(clickStatsService.consume(event), "second replay must be skipped");
        assertEquals(1, clickDailyMapper.sumClicks(linkId), "replays must not inflate the aggregate");
    }

    @Test
    void distinctEventsAndDaysBucketSeparately() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/idem2-" + System.currentTimeMillis());
        long linkId = linkIdOf(code, token);
        long stamp = System.currentTimeMillis();
        syntheticCodes.add(code);
        syntheticLinkIds.add(linkId);

        ClickEvent yesterday = new ClickEvent("it-a" + stamp, 1L, linkId, code,
                null, null, null, null, LocalDateTime.now().minusDays(1));
        ClickEvent today = new ClickEvent("it-b" + stamp, 1L, linkId, code,
                null, null, null, null, LocalDateTime.now());
        syntheticEventIds.add(yesterday.getEventId());
        syntheticEventIds.add(today.getEventId());

        assertTrue(clickStatsService.consume(yesterday));
        assertTrue(clickStatsService.consume(today));

        assertEquals(2, clickDailyMapper.sumClicks(linkId));
        var stats = clickStatsService.stats(linkId, code, 7);
        assertEquals(2, stats.getTotalClicks());
        assertEquals(7, stats.getDaily().size());
        assertEquals(1, stats.getDaily().get(5).getClicks(), "yesterday bucket");
        assertEquals(1, stats.getDaily().get(6).getClicks(), "today bucket");
    }

    private long linkIdOf(String code, String token) throws Exception {
        var resp = get("/api/links/" + code, token);
        return body(resp).path("data").path("id").asLong();
    }
}
