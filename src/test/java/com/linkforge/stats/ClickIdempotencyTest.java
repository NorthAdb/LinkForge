package com.linkforge.stats;

import com.linkforge.AbstractIntegrationTest;
import com.linkforge.stats.mapper.ClickDailyMapper;
import com.linkforge.stats.mq.ClickEvent;
import com.linkforge.stats.service.ClickStatsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-idem:",
        "linkforge.rate-limit.register-max=1000"
})
@ActiveProfiles("test")
class ClickIdempotencyTest extends AbstractIntegrationTest {

    @Autowired
    private ClickStatsService clickStatsService;

    @Autowired
    private ClickDailyMapper clickDailyMapper;

    @Test
    void sameEventIdCountsOnceAcrossReplays() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/idem-" + System.currentTimeMillis());
        long linkId = linkIdOf(code, token);

        ClickEvent event = new ClickEvent("it-" + System.currentTimeMillis(), 1L, linkId, code,
                null, "127.0.0.1", "it-ua", null, LocalDateTime.now());

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

        assertTrue(clickStatsService.consume(new ClickEvent("it-a" + stamp, 1L, linkId, code,
                null, null, null, null, LocalDateTime.now().minusDays(1))));
        assertTrue(clickStatsService.consume(new ClickEvent("it-b" + stamp, 1L, linkId, code,
                null, null, null, null, LocalDateTime.now())));

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
