package com.linkforge.webhook;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.linkforge.AbstractIntegrationTest;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.webhook.entity.WebhookAggregateBuffer;
import com.linkforge.webhook.entity.WebhookDelivery;
import com.linkforge.webhook.entity.WebhookEndpoint;
import com.linkforge.webhook.mapper.WebhookAggregateBufferMapper;
import com.linkforge.webhook.mapper.WebhookDeliveryMapper;
import com.linkforge.webhook.mapper.WebhookEndpointMapper;
import com.linkforge.webhook.mapper.WebhookFlushStateMapper;
import com.linkforge.webhook.service.WebhookAggregateFlusher;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit M3 contract tests for the transactional digest drain (reversing the
 * audit probes {@code snapshotDeletionLosesIncrementOnExistingBucket} and
 * {@code laterBucketSurvivesDeleteButNextFlushDropsIt}). A drain locks the
 * window's buckets, allocates a persistent per-endpoint flush sequence, and
 * commits deliveries + buffer delete + sequence in ONE transaction — clicks
 * are delivered exactly once, and a failed drain rolls back whole.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WebhookAggregateDrainIT extends AbstractIntegrationTest {

    // synthetic endpoint id (no FKs on buffer/delivery/state), far from real rows
    private static final long ENDPOINT_ID = 987_655L;
    private static final long WINDOW_A = 1_700_100_000L;
    private static final long WINDOW_B = 1_700_200_000L;
    private static final long WINDOW_C = 1_700_300_000L;
    private static final long WINDOW_D = 1_700_400_000L;

    /** Arms the failing flusher below: the next delivery insert throws once. */
    private static final AtomicBoolean FAIL_NEXT_INSERT = new AtomicBoolean();

    @TestConfiguration
    static class FailingFlusherConfig {

        @Bean
        @Primary
        WebhookAggregateFlusher failingFlusher(WebhookAggregateBufferMapper bufferMapper,
                                               WebhookDeliveryMapper deliveryMapper,
                                               WebhookEndpointMapper endpointMapper,
                                               WebhookFlushStateMapper flushStateMapper,
                                               LinkForgeProperties properties,
                                               com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                               MeterRegistry meterRegistry,
                                               PlatformTransactionManager transactionManager) {
            return new WebhookAggregateFlusher(bufferMapper, deliveryMapper, endpointMapper,
                    flushStateMapper, properties, objectMapper, meterRegistry, transactionManager) {
                @Override
                protected void insertDelivery(WebhookDelivery delivery) {
                    if (FAIL_NEXT_INSERT.compareAndSet(true, false)) {
                        throw new IllegalStateException("injected delivery insert failure (M3 drain test)");
                    }
                    super.insertDelivery(delivery);
                }
            };
        }
    }

    @Autowired
    private WebhookAggregateBufferMapper bufferMapper;

    @Autowired
    private WebhookDeliveryMapper deliveryMapper;

    @Autowired
    private WebhookAggregateFlusher flusher;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void disarmFailure() {
        FAIL_NEXT_INSERT.set(false);
    }

    @AfterEach
    void cleanTables() {
        bufferMapper.delete(new LambdaQueryWrapper<WebhookAggregateBuffer>()
                .eq(WebhookAggregateBuffer::getEndpointId, ENDPOINT_ID));
        deliveryMapper.delete(new LambdaQueryWrapper<WebhookDelivery>()
                .eq(WebhookDelivery::getEndpointId, ENDPOINT_ID));
        jdbc.update("DELETE FROM lf_webhook_flush_state WHERE endpoint_id = ?", ENDPOINT_ID);
    }

    private WebhookEndpoint endpoint() {
        WebhookEndpoint endpoint = new WebhookEndpoint();
        endpoint.setId(ENDPOINT_ID);
        endpoint.setTenantId(1L);
        endpoint.setDeliveryMode(WebhookEndpoint.MODE_AGGREGATE);
        endpoint.setAggregateWindowSeconds(60);
        return endpoint;
    }

    private void upsert(long windowStart, long linkId, String code) {
        bufferMapper.upsertClick(1L, ENDPOINT_ID, windowStart, linkId, code, false, null, null, null);
    }

    private List<WebhookDelivery> deliveries() {
        return deliveryMapper.selectList(new LambdaQueryWrapper<WebhookDelivery>()
                .eq(WebhookDelivery::getEndpointId, ENDPOINT_ID)
                .orderByAsc(WebhookDelivery::getId));
    }

    private long clicksFor(List<WebhookDelivery> deliveries, long linkId) throws Exception {
        long total = 0;
        for (WebhookDelivery delivery : deliveries) {
            JsonNode links = om.readTree(delivery.getPayload()).path("data").path("links");
            for (JsonNode link : links) {
                if (link.path("linkId").asLong() == linkId) {
                    total += link.path("clicks").asLong();
                }
            }
        }
        return total;
    }

    private int bufferedRows(long windowStart) {
        return bufferMapper.selectWindow(ENDPOINT_ID, windowStart).size();
    }

    /**
     * Reverses {@code snapshotDeletionLosesIncrementOnExistingBucket}: an
     * increment racing the drain — whether it lands before the drain's locked
     * read, blocks on the row lock, or arrives after the commit — must be
     * delivered exactly once across all drains.
     */
    @Test
    void concurrentIncrementTotalsExactly() throws Exception {
        upsert(WINDOW_A, 201L, "drainA");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> flush = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                flusher.flushWindow(endpoint(), WINDOW_A);
                return null;
            });
            Future<?> increment = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                upsert(WINDOW_A, 201L, "drainA");
                return null;
            });
            flush.get(60, TimeUnit.SECONDS);
            increment.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        // drain whatever the racing increment left behind as a fresh bucket
        flusher.flushWindow(endpoint(), WINDOW_A);

        List<WebhookDelivery> all = deliveries();
        assertEquals(2, clicksFor(all, 201L),
                "the seeded click and the racing increment are delivered exactly once in total");
        assertEquals(0, bufferedRows(WINDOW_A), "the window is fully drained");
        Set<String> eventIds = new HashSet<>();
        for (WebhookDelivery delivery : all) {
            assertTrue(eventIds.add(delivery.getEventId()),
                    "no delivery payload appears twice under one event id: " + delivery.getEventId());
        }
    }

    /**
     * Reverses {@code laterBucketSurvivesDeleteButNextFlushDropsIt}: a NEW
     * bucket arriving in an already-flushed window is delivered by the next
     * drain under a different event id (the next flush sequence).
     */
    @Test
    void lateBucketGetsDistinctDelivery() throws Exception {
        upsert(WINDOW_B, 501L, "drainX");
        flusher.flushWindow(endpoint(), WINDOW_B);
        List<WebhookDelivery> first = deliveries();
        assertEquals(1, first.size());
        assertEquals(1, clicksFor(first, 501L));
        assertEquals(0, bufferedRows(WINDOW_B));

        upsert(WINDOW_B, 502L, "drainY");
        flusher.flushWindow(endpoint(), WINDOW_B);

        List<WebhookDelivery> both = deliveries();
        assertEquals(2, both.size(), "the late bucket is delivered by a second drain");
        assertNotEquals(both.get(0).getEventId(), both.get(1).getEventId(),
                "the second drain must not reuse the first drain's event id");
        assertEquals(1, clicksFor(both, 502L), "the late bucket is in the second delivery's payload");
        assertEquals(1, clicksFor(both, 501L), "the first bucket is not delivered twice");
        assertEquals(0, bufferedRows(WINDOW_B), "no deleted-but-undelivered rows remain");
    }

    /**
     * Audit §4 M3 C {@code crashOrDuplicateBatchDoesNotLoseOrDoubleCount},
     * failure half: an insert failure after the payload is built rolls the
     * whole drain back — buffer untouched, no delivery, sequence unconsumed —
     * and the retry reproduces the same event id and exact totals.
     */
    @Test
    void insertFailureRollsBackWholeBatch() throws Exception {
        upsert(WINDOW_C, 401L, "drainF1");
        upsert(WINDOW_C, 402L, "drainF2");
        List<WebhookAggregateBuffer> before = bufferMapper.selectWindow(ENDPOINT_ID, WINDOW_C);
        assertEquals(2, before.size());

        FAIL_NEXT_INSERT.set(true);
        assertThrows(RuntimeException.class, () -> flusher.flushWindow(endpoint(), WINDOW_C),
                "the injected insert failure must abort the drain");

        List<WebhookAggregateBuffer> after = bufferMapper.selectWindow(ENDPOINT_ID, WINDOW_C);
        assertEquals(before.stream().map(WebhookAggregateBuffer::getId).toList(),
                after.stream().map(WebhookAggregateBuffer::getId).toList(),
                "the buffer rows survive a rolled-back drain with their ids");
        assertEquals(before.stream().map(WebhookAggregateBuffer::getClicks).toList(),
                after.stream().map(WebhookAggregateBuffer::getClicks).toList(),
                "and with their click counts");
        assertTrue(deliveries().isEmpty(), "no delivery row survives the rollback");
        Integer seq = jdbc.queryForObject(
                "SELECT COALESCE((SELECT flush_seq FROM lf_webhook_flush_state"
                        + " WHERE endpoint_id = ?), 0)",
                Integer.class, ENDPOINT_ID);
        assertEquals(0, seq.intValue(), "the failed drain consumed no sequence");

        flusher.flushWindow(endpoint(), WINDOW_C);
        List<WebhookDelivery> retried = deliveries();
        assertEquals(1, retried.size());
        assertEquals("digest-" + ENDPOINT_ID + "-" + WINDOW_C + "-1", retried.get(0).getEventId(),
                "the retry reproduces the rolled-back drain's event id");
        assertEquals(1, clicksFor(retried, 401L));
        assertEquals(1, clicksFor(retried, 402L));
        assertEquals(0, bufferedRows(WINDOW_C));
    }

    /** Two flushers racing the same window consume its buckets exactly once. */
    @Test
    void concurrentFlushersConsumeOnce() throws Exception {
        upsert(WINDOW_D, 301L, "drainC1");
        upsert(WINDOW_D, 301L, "drainC1");
        upsert(WINDOW_D, 302L, "drainC2");
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                flusher.flushWindow(endpoint(), WINDOW_D);
                return null;
            });
            Future<?> second = pool.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                flusher.flushWindow(endpoint(), WINDOW_D);
                return null;
            });
            first.get(60, TimeUnit.SECONDS);
            second.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<WebhookDelivery> all = deliveries();
        assertEquals(2, clicksFor(all, 301L), "bucket 301's two clicks are consumed exactly once");
        assertEquals(1, clicksFor(all, 302L), "bucket 302's click is consumed exactly once");
        assertEquals(0, bufferedRows(WINDOW_D));
        Set<String> eventIds = new HashSet<>();
        for (WebhookDelivery delivery : all) {
            assertTrue(eventIds.add(delivery.getEventId()),
                    "duplicate event id across racing drains: " + delivery.getEventId());
        }
    }
}
