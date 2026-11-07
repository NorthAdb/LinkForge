package com.linkforge.webhook;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linkforge.AbstractIntegrationTest;
import com.linkforge.webhook.entity.WebhookAggregateBuffer;
import com.linkforge.webhook.mapper.WebhookAggregateBufferMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T-M2: flush cleanup deletes only the snapshotted rows. A bucket a
 * concurrent consume writes into the same window AFTER the snapshot was
 * taken is not part of the digest, so it must survive for the next flush —
 * the old whole-window delete dropped it silently.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WebhookAggregateSnapshotDeleteIT extends AbstractIntegrationTest {

    // synthetic endpoint id (the buffer table carries no FK), far from real rows
    private static final long ENDPOINT_ID = 987_654L;
    private static final long WINDOW_START = 1_700_000_000L;

    @Autowired
    private WebhookAggregateBufferMapper bufferMapper;

    @AfterEach
    void cleanBuffer() {
        bufferMapper.delete(new LambdaQueryWrapper<WebhookAggregateBuffer>()
                .eq(WebhookAggregateBuffer::getEndpointId, ENDPOINT_ID));
    }

    private void upsert(long linkId, String code) {
        bufferMapper.upsertClick(1L, ENDPOINT_ID, WINDOW_START, linkId, code, false, null, null, null);
    }

    @Test
    void bucketWrittenAfterTheSnapshotSurvivesFlushCleanup() {
        upsert(101L, "snapA");
        upsert(102L, "snapB");
        List<WebhookAggregateBuffer> snapshot = bufferMapper.selectWindow(ENDPOINT_ID, WINDOW_START);
        assertEquals(2, snapshot.size());

        // a consume lands in the same window while the flush is in flight
        upsert(103L, "snapC");

        int deleted = bufferMapper.deleteSnapshotRows(ENDPOINT_ID, WINDOW_START,
                snapshot.stream().map(WebhookAggregateBuffer::getId).toList());
        assertEquals(2, deleted, "exactly the snapshotted rows go");

        List<WebhookAggregateBuffer> remaining = bufferMapper.selectWindow(ENDPOINT_ID, WINDOW_START);
        assertEquals(1, remaining.size(), "the post-snapshot bucket must survive for the next flush");
        assertEquals(103L, remaining.get(0).getLinkId());
        assertEquals(1, remaining.get(0).getClicks().intValue());
    }
}
