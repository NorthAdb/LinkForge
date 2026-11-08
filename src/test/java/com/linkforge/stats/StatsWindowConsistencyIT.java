package com.linkforge.stats;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.AbstractIntegrationTest;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.link.mapper.LinkVariantMapper;
import com.linkforge.link.mapper.ShortLinkMapper;
import com.linkforge.stats.entity.ClickDaily;
import com.linkforge.stats.entity.ClickLog;
import com.linkforge.stats.entity.ConsumedEvent;
import com.linkforge.stats.mapper.ClickDailyMapper;
import com.linkforge.stats.mapper.ClickDimDailyMapper;
import com.linkforge.stats.mapper.ClickLogMapper;
import com.linkforge.stats.mapper.ConsumedEventMapper;
import com.linkforge.stats.mq.ClickEvent;
import com.linkforge.stats.service.ClickStatsService;
import com.linkforge.webhook.service.WebhookService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T-M1: one stats definition throughout. totalClicks used to be the all-time
 * aggregate total while totalUv covered only the requested window, and the
 * daily clicks/UV columns came from two different pipelines. Now every figure
 * covers the same window off the same detail rows: the window total equals
 * the sum of the daily clicks, and clicks outside the window count nowhere.
 *
 * <p>S/N wave (audit S4-1) adds the snapshot half of the contract in
 * {@link #concurrentInsertKeepsOneSnapshot}: the three reads of one response
 * share a single read-only RR snapshot, forced into the open by the seam
 * below (the PR-A insertSuccessor precedent — latches, never sleeps; the
 * seam is inert for the T-M1 test above because its latches stay unset).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "linkforge.rate-limit.key-prefix=linkforge:rl:it-statswin:",
        "linkforge.rate-limit.register-max=1000"
})
@ActiveProfiles("test")
class StatsWindowConsistencyIT extends AbstractIntegrationTest {

    /** The latching ClickStatsService, scoped to this class's context only. */
    @TestConfiguration
    static class SeamConfig {

        @Bean
        @Primary
        ClickStatsService latchingClickStatsService(ConsumedEventMapper consumedEventMapper,
                                                    ClickDailyMapper clickDailyMapper,
                                                    ClickDimDailyMapper clickDimDailyMapper,
                                                    ClickLogMapper clickLogMapper,
                                                    LinkVariantMapper linkVariantMapper,
                                                    ShortLinkMapper shortLinkMapper,
                                                    LinkForgeProperties properties,
                                                    WebhookService webhookService,
                                                    PlatformTransactionManager transactionManager) {
            return new LatchingClickStatsService(consumedEventMapper, clickDailyMapper,
                    clickDimDailyMapper, clickLogMapper, linkVariantMapper, shortLinkMapper,
                    properties, webhookService, transactionManager);
        }
    }

    static class LatchingClickStatsService extends ClickStatsService {

        /** When set, the second read counts {@code firstReadDone} down and
         * blocks on {@code insertLanded} — inside the snapshot transaction,
         * after the first read has returned. */
        static volatile CountDownLatch firstReadDone;
        static volatile CountDownLatch insertLanded;

        LatchingClickStatsService(ConsumedEventMapper consumedEventMapper,
                                  ClickDailyMapper clickDailyMapper,
                                  ClickDimDailyMapper clickDimDailyMapper,
                                  ClickLogMapper clickLogMapper,
                                  LinkVariantMapper linkVariantMapper,
                                  ShortLinkMapper shortLinkMapper,
                                  LinkForgeProperties properties,
                                  WebhookService webhookService,
                                  PlatformTransactionManager transactionManager) {
            super(consumedEventMapper, clickDailyMapper, clickDimDailyMapper, clickLogMapper,
                    linkVariantMapper, shortLinkMapper, properties, webhookService, transactionManager);
        }

        @Override
        protected Map<LocalDate, Long> readDailyClicks(Long linkId, LocalDateTime from,
                                                       LocalDateTime toExclusive) {
            CountDownLatch done = firstReadDone;
            if (done != null) {
                done.countDown();
                try {
                    if (!insertLanded.await(60, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test insert never landed");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }
            return super.readDailyClicks(linkId, from, toExclusive);
        }

        static void resetSeam() {
            firstReadDone = null;
            insertLanded = null;
        }
    }

    @Autowired
    private ClickStatsService clickStatsService;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearSeam() {
        LatchingClickStatsService.resetSeam();
    }

    @Autowired
    private ClickDailyMapper clickDailyMapper;

    @Autowired
    private ConsumedEventMapper consumedEventMapper;

    @Autowired
    private ClickLogMapper clickLogMapper;

    @Autowired
    private ClickDimDailyMapper clickDimDailyMapper;

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
            clickLogMapper.delete(new LambdaQueryWrapper<ClickLog>()
                    .in(ClickLog::getCode, syntheticCodes));
        }
        if (!syntheticLinkIds.isEmpty()) {
            clickDimDailyMapper.delete(new LambdaQueryWrapper<com.linkforge.stats.entity.ClickDimDaily>()
                    .in(com.linkforge.stats.entity.ClickDimDaily::getLinkId, syntheticLinkIds));
        }
    }

    private void consume(String eventId, long linkId, String code, String ip, LocalDateTime at) {
        clickStatsService.consume(new ClickEvent(eventId, 1L, linkId, code,
                null, ip, "it-ua", null, at, null));
        syntheticEventIds.add(eventId);
    }

    @Test
    void totalsAndDailySeriesShareOneWindowAndOneSource() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/statswin-" + System.currentTimeMillis());
        syntheticCodes.add(code);
        long linkId = body(get("/api/links/" + code, token)).path("data").path("id").asLong();
        syntheticLinkIds.add(linkId);
        String stamp = String.valueOf(System.nanoTime());

        // today: 3 clicks from 2 distinct IPs; 10 days ago: 2 more clicks
        consume("win-a" + stamp, linkId, code, "10.0.0.1", LocalDateTime.now());
        consume("win-b" + stamp, linkId, code, "10.0.0.1", LocalDateTime.now());
        consume("win-c" + stamp, linkId, code, "10.0.0.2", LocalDateTime.now());
        consume("win-d" + stamp, linkId, code, "10.0.0.9", LocalDateTime.now().minusDays(10));
        consume("win-e" + stamp, linkId, code, "10.0.0.9", LocalDateTime.now().minusDays(10));

        var week = body(get("/api/links/" + code + "/stats", token)).path("data");
        assertEquals(3, week.path("totalClicks").asLong(),
                "window total must exclude the clicks from 10 days ago (all-time total was the bug)");
        assertEquals(2, week.path("totalUv").asLong(), "distinct IPs inside the same window");
        assertEquals(7, week.path("daily").size());
        long dailySum = 0;
        for (var day : week.path("daily")) {
            dailySum += day.path("clicks").asLong();
        }
        assertEquals(week.path("totalClicks").asLong(), dailySum,
                "totalClicks is exactly the sum of the daily clicks column");
        var today = week.path("daily").get(6);
        assertEquals(3, today.path("clicks").asLong());
        assertEquals(2, today.path("uv").asLong(), "a day's UV can never exceed its clicks");

        var month = body(get("/api/links/" + code + "/stats?days=30", token)).path("data");
        assertEquals(5, month.path("totalClicks").asLong(), "the wider window sees all five clicks");
        assertEquals(30, month.path("daily").size());
    }

    private void insertClickRow(long tenantId, long linkId, String code, String ip) {
        jdbc.update("INSERT INTO lf_click_log (tenant_id, link_id, code, ip, clicked_at) "
                + "VALUES (?, ?, ?, ?, NOW())", tenantId, linkId, code, ip);
        syntheticCodes.add(code);
    }

    @Test
    void concurrentInsertKeepsOneSnapshot() throws Exception {
        String token = registerForToken();
        String code = createLink(token, "https://example.com/snap-" + unique(""));
        long linkId = body(get("/api/links/" + code, token)).path("data").path("id").asLong();
        Long tenantId = jdbc.queryForObject(
                "SELECT tenant_id FROM lf_short_link WHERE code = ?", Long.class, code);
        syntheticCodes.add(code);
        insertClickRow(tenantId, linkId, code, "10.0.0.1");

        LatchingClickStatsService.firstReadDone = new CountDownLatch(1);
        LatchingClickStatsService.insertLanded = new CountDownLatch(1);
        CompletableFuture<ResponseEntity<String>> responseFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return get("/api/links/" + code + "/stats?days=7", token);
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            }
        });

        // The request thread is inside its snapshot, first read returned.
        assertTrue(LatchingClickStatsService.firstReadDone.await(30, TimeUnit.SECONDS),
                "stats request never reached the second read");
        // Two clicks from a brand-new IP land, committed, mid-response, on
        // this thread's own (second) connection.
        insertClickRow(tenantId, linkId, code, "10.0.0.2");
        insertClickRow(tenantId, linkId, code, "10.0.0.2");
        LatchingClickStatsService.insertLanded.countDown();

        ResponseEntity<String> resp = responseFuture.get(60, TimeUnit.SECONDS);
        assertEquals(200, resp.getStatusCode().value(), resp.getBody());
        var data = body(resp).path("data");
        // One snapshot: the response describes exactly the click set that
        // existed when it started — 1 click, UV 1 — not a blend of the
        // pre-insert UV (1) with post-insert totals (3 clicks / daily UV 2),
        // which is what three auto-commit reads used to produce here.
        assertEquals(1, data.path("totalClicks").asLong(), data::toString);
        assertEquals(1, data.path("totalUv").asLong(), data::toString);
        var today = data.path("daily").get(6);
        assertEquals(LocalDate.now().toString(), today.path("date").asText());
        assertEquals(1, today.path("clicks").asLong(), data::toString);
        assertEquals(1, today.path("uv").asLong(), data::toString);
    }
}
