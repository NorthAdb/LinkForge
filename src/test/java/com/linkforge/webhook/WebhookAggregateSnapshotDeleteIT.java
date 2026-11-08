package com.linkforge.webhook;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.AbstractIntegrationTest;
import com.linkforge.webhook.entity.WebhookAggregateBuffer;
import com.linkforge.webhook.entity.WebhookDelivery;
import com.linkforge.webhook.entity.WebhookEndpoint;
import com.linkforge.webhook.mapper.WebhookAggregateBufferMapper;
import com.linkforge.webhook.mapper.WebhookDeliveryMapper;
import com.linkforge.webhook.service.WebhookAggregateFlusher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * T-M2's scenario under the audit-M3 drain contract (this class previously
 * asserted the mapper-level snapshot delete directly). A bucket a consume
 * writes into the same window AFTER a drain is not part of that drain's
 * digest, so it must survive and be delivered by the NEXT drain — under its
 * own flush sequence and event id, never deleted unsent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WebhookAggregateSnapshotDeleteIT extends AbstractIntegrationTest {

    // synthetic endpoint id (the buffer/delivery/state tables carry no FK), far from real rows
    private static final long ENDPOINT_ID = 987_654L;
    private static final long WINDOW_START = 1_700_000_000L;

    @Autowired
    private WebhookAggregateBufferMapper bufferMapper;

    @Autowired
    private WebhookDeliveryMapper deliveryMapper;

    @Autowired
    private WebhookAggregateFlusher flusher;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper objectMapper;

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

    private void upsert(long linkId, String code) {
        bufferMapper.upsertClick(1L, ENDPOINT_ID, WINDOW_START, linkId, code, false, null, null, null);
    }

    private List<WebhookDelivery> deliveries() {
        return deliveryMapper.selectList(new LambdaQueryWrapper<WebhookDelivery>()
                .eq(WebhookDelivery::getEndpointId, ENDPOINT_ID)
                .orderByAsc(WebhookDelivery::getId));
    }

    private long clicksFor(List<WebhookDelivery> deliveries, long linkId) throws Exception {
        long total = 0;
        for (WebhookDelivery delivery : deliveries) {
            JsonNode links = objectMapper.readTree(delivery.getPayload()).path("data").path("links");
            for (JsonNode link : links) {
                if (link.path("linkId").asLong() == linkId) {
                    total += link.path("clicks").asLong();
                }
            }
        }
        return total;
    }

    @Test
    void bucketWrittenAfterTheDrainSurvivesForTheNextDrain() throws Exception {
        upsert(101L, "snapA");
        upsert(102L, "snapB");
        flusher.flushWindow(endpoint(), WINDOW_START);

        List<WebhookDelivery> firstDrain = deliveries();
        assertEquals(1, firstDrain.size(), "the first drain emits exactly one digest");
        assertEquals(1, clicksFor(firstDrain, 101L));
        assertEquals(1, clicksFor(firstDrain, 102L));
        assertEquals(0, bufferMapper.selectWindow(ENDPOINT_ID, WINDOW_START).size(),
                "the drained buckets are gone");

        // a consume lands in the same window after the drain committed
        upsert(103L, "snapC");
        flusher.flushWindow(endpoint(), WINDOW_START);

        List<WebhookDelivery> bothDrains = deliveries();
        assertEquals(2, bothDrains.size(), "the post-drain bucket gets its own digest");
        assertNotEquals(firstDrain.get(0).getEventId(), bothDrains.get(1).getEventId(),
                "each drain carries its own flush sequence in the event id");
        assertEquals(1, clicksFor(bothDrains, 103L), "the late bucket is delivered, not dropped");
        assertEquals(0, bufferMapper.selectWindow(ENDPOINT_ID, WINDOW_START).size(),
                "nothing is left behind — and nothing was deleted unsent");
    }
}
