package com.linkforge.webhook.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.webhook.entity.WebhookAggregateBuffer;
import com.linkforge.webhook.entity.WebhookDelivery;
import com.linkforge.webhook.entity.WebhookEndpoint;
import com.linkforge.webhook.mapper.WebhookAggregateBufferMapper;
import com.linkforge.webhook.mapper.WebhookDeliveryMapper;
import com.linkforge.webhook.mapper.WebhookEndpointMapper;
import com.linkforge.webhook.mapper.WebhookFlushStateMapper;
import com.linkforge.webhook.support.AggregateWindows;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C1 wave C (D-C1-21..23): turns AGGREGATE buffer windows into digest deliveries
 * through the UNCHANGED relay machinery — parts are plain PENDING lf_webhook_delivery
 * rows the existing signer/SSRF/retry path drains. Triggered by the event threshold
 * (from WebhookService, inside the consume transaction) and by the time sweep.
 *
 * PROPAGATION_REQUIRED on {@link TransactionTemplate}: the threshold call joins the
 * consume transaction (digest rows commit or roll back with the statistics), the
 * sweep opens its own.
 *
 * <p>Audit fix M3 — the drain contract: one (endpoint, window) drain is ONE
 * transaction that (1) reads the window's buckets with row locks, (2) allocates
 * the endpoint's next persistent flush sequence (V15, lf_webhook_flush_state),
 * (3) inserts the digest deliveries under event ids
 * {@code digest-{endpointId}-{windowStart}-{seq}}, and (4) deletes exactly the
 * locked rows. Any failure — including a DuplicateKeyException — rolls the
 * WHOLE drain back: the buffer stays intact, the sequence stays unconsumed,
 * and a retry reproduces the same event ids. The pre-M3 snapshot scheme read
 * without locks and deleted by snapshot id after swallowing DuplicateKey, which
 * lost two kinds of clicks: increments that landed on an already-snapshotted
 * bucket, and late NEW buckets in an already-flushed window (their batch
 * reused the bare window event id, collided, and was deleted unsent).
 */
@Slf4j
@Component
public class WebhookAggregateFlusher {

    public static final String EVENT_TYPE_DIGEST = "link.clicks.digest";
    /** D-C1-23: digest parts hold at most this many link buckets. */
    static final int MAX_LINK_BUCKETS_PER_PART = 500;
    /** D-C1-25: buffered windows die as FAILED intents when the endpoint is deleted. */
    static final String DELETED_WITH_BUFFER_ERROR = "endpoint deleted with pending aggregate buffer";

    private static final String FLUSH_COUNTER = "linkforge.webhook.aggregate.flush";

    private final WebhookAggregateBufferMapper bufferMapper;
    private final WebhookDeliveryMapper deliveryMapper;
    private final WebhookEndpointMapper endpointMapper;
    private final WebhookFlushStateMapper flushStateMapper;
    private final LinkForgeProperties properties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate tx;

    public WebhookAggregateFlusher(WebhookAggregateBufferMapper bufferMapper,
                                   WebhookDeliveryMapper deliveryMapper,
                                   WebhookEndpointMapper endpointMapper,
                                   WebhookFlushStateMapper flushStateMapper,
                                   LinkForgeProperties properties,
                                   ObjectMapper objectMapper,
                                   MeterRegistry meterRegistry,
                                   PlatformTransactionManager transactionManager) {
        this.bufferMapper = bufferMapper;
        this.deliveryMapper = deliveryMapper;
        this.endpointMapper = endpointMapper;
        this.flushStateMapper = flushStateMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * D-C1-21 time trigger: every expired AGGREGATE window, oldest first. One bad
     * window must not stall the others, so failures are logged and the sweep moves
     * on (the error is already counted inside flushWindow).
     */
    @Scheduled(fixedDelayString = "${linkforge.webhook.aggregate-sweep-fixed-delay-ms:5000}",
            initialDelayString = "${linkforge.webhook.aggregate-sweep-initial-delay-ms:3000}")
    public void sweep() {
        if (!properties.getWebhook().isEnabled()) {
            return;
        }
        List<WebhookAggregateBuffer> expired = bufferMapper.selectExpiredWindows();
        for (WebhookAggregateBuffer window : expired) {
            WebhookEndpoint endpoint = endpointMapper.selectById(window.getEndpointId());
            if (endpoint == null || !WebhookEndpoint.MODE_AGGREGATE.equals(endpoint.getDeliveryMode())) {
                continue;
            }
            try {
                flushWindow(endpoint, window.getWindowStart());
            } catch (Exception ex) {
                log.warn("aggregate sweep flush of endpoint {} window {} failed: {}",
                        window.getEndpointId(), window.getWindowStart(), ex.getMessage());
            }
        }
    }

    /** Threshold trigger (joins the consume tx) and switch/sweep trigger (own tx). */
    public void flushWindow(WebhookEndpoint endpoint, long windowStart) {
        try {
            tx.executeWithoutResult(status ->
                    doFlush(endpoint, windowStart, WebhookDelivery.STATUS_PENDING, null));
        } catch (Exception ex) {
            meterRegistry.counter(FLUSH_COUNTER, "result", "error").increment();
            throw ex instanceof RuntimeException runtime ? runtime : new IllegalStateException(ex);
        }
    }

    /**
     * D-C1-25: AGGREGATE -> PER_CLICK flushes every window the endpoint still
     * buffers BEFORE the mode update is saved. Each window is its own transaction,
     * so a crash mid-way leaves the rest for the next attempt (idempotent re-flush).
     */
    public void flushAllOpenWindows(WebhookEndpoint endpoint) {
        for (long windowStart : bufferMapper.selectWindowStarts(endpoint.getId())) {
            flushWindow(endpoint, windowStart);
        }
    }

    /**
     * D-C1-25: delete keeps the delivery intent — each buffered window becomes
     * FAILED digest rows with {@code lastError = "endpoint deleted with pending
     * aggregate buffer"}, the buffer rows go, then the endpoint row. FAILED rows are
     * never picked by the relay, so nothing is sent; the intent stays inspectable.
     */
    public void discardBufferedOnDelete(WebhookEndpoint endpoint) {
        for (long windowStart : bufferMapper.selectWindowStarts(endpoint.getId())) {
            tx.executeWithoutResult(status ->
                    doFlush(endpoint, windowStart, WebhookDelivery.STATUS_FAILED, DELETED_WITH_BUFFER_ERROR));
        }
    }

    private void doFlush(WebhookEndpoint endpoint, long windowStart, String status, String lastError) {
        // M3 drain step 1: lock the window's buckets. Increments on a locked
        // bucket block until this drain commits and then land as a FRESH row
        // (the locked row is deleted below); new buckets insert alongside and
        // simply are not in this drain. Either way nothing is deleted unsent.
        List<WebhookAggregateBuffer> rows =
                bufferMapper.selectByEndpointWindowForUpdate(endpoint.getId(), windowStart);
        if (rows.isEmpty()) {
            return; // empty window: no drain, and no sequence is consumed
        }
        // M3 drain step 2: allocate this drain's sequence under the state row lock.
        long seq = nextFlushSeq(endpoint.getId());
        // M3 drain steps 3+4: deliveries from the LOCKED counts, then delete
        // exactly the locked rows — all inside the caller's transaction, so any
        // failure rolls the deliveries, the delete AND the sequence back.
        emitParts(endpoint, windowStart, rows, status, lastError, seq);
        bufferMapper.deleteSnapshotRows(endpoint.getId(), windowStart,
                rows.stream().map(WebhookAggregateBuffer::getId).toList());
        if (WebhookDelivery.STATUS_PENDING.equals(status)) {
            meterRegistry.counter(FLUSH_COUNTER, "result", "ok").increment();
        }
    }

    /** Allocates the endpoint's next drain sequence inside the current transaction. */
    private long nextFlushSeq(Long endpointId) {
        flushStateMapper.ensureRow(endpointId);
        Long current = flushStateMapper.selectSeqForUpdate(endpointId);
        long next = (current == null ? 0L : current) + 1;
        flushStateMapper.updateSeq(endpointId, next);
        return next;
    }

    /**
     * Deterministic slices of at most 500 buckets (D-C1-23), part 1 carrying the
     * drain's digest-{endpointId}-{windowStart}-{seq} id (later parts append
     * -part{n}). Beyond aggregate-max-parts only the first parts are emitted and
     * the last one flags data.truncated=true (never silent). A failed insert is
     * NOT swallowed: it aborts the whole drain transaction (M3), because with a
     * per-drain sequence a duplicate event id can only mean this very drain is
     * being replayed against its own uncommitted work — never another batch's.
     */
    private void emitParts(WebhookEndpoint endpoint, long windowStart, List<WebhookAggregateBuffer> rows,
                           String status, String lastError, long seq) {
        int partCount = (rows.size() + MAX_LINK_BUCKETS_PER_PART - 1) / MAX_LINK_BUCKETS_PER_PART;
        int maxParts = Math.max(1, properties.getWebhook().getAggregateMaxParts());
        boolean truncated = partCount > maxParts;
        int emitted = Math.min(partCount, maxParts);
        long totalClicks = rows.stream().mapToLong(r -> r.getClicks() == null ? 0 : r.getClicks()).sum();
        int windowSeconds = windowSeconds(endpoint);
        LocalDateTime now = LocalDateTime.now();
        try {
            for (int i = 0; i < emitted; i++) {
                int partIndex = i + 1;
                String eventId = "digest-" + endpoint.getId() + "-" + windowStart + "-" + seq
                        + (partIndex > 1 ? "-part" + partIndex : "");
                String payload = objectMapper.writeValueAsString(buildPayload(eventId, windowStart, windowSeconds,
                        partIndex, partCount, totalClicks,
                        rows.subList(i * MAX_LINK_BUCKETS_PER_PART, Math.min((i + 1) * MAX_LINK_BUCKETS_PER_PART, rows.size())),
                        truncated && partIndex == emitted));
                WebhookDelivery delivery = new WebhookDelivery();
                delivery.setTenantId(endpoint.getTenantId());
                delivery.setEndpointId(endpoint.getId());
                delivery.setEventId(eventId);
                delivery.setEventType(EVENT_TYPE_DIGEST);
                delivery.setPayload(payload);
                delivery.setStatus(status);
                delivery.setAttempts(0);
                delivery.setCreatedAt(now);
                delivery.setLastError(lastError);
                insertDelivery(delivery);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("digest payload serialization failed", ex);
        }
        if (truncated) {
            log.warn("aggregate window {} of endpoint {} needed {} digest parts, emitted {} (truncated=true)",
                    windowStart, endpoint.getId(), partCount, emitted);
        }
    }

    /**
     * Test seam (audit M3): the drain's single delivery insert. Tests override
     * this to force a failure after the payload is built and prove the whole
     * drain — deliveries, buffer delete and sequence — rolls back.
     */
    protected void insertDelivery(WebhookDelivery delivery) {
        deliveryMapper.insert(delivery);
    }

    private Map<String, Object> buildPayload(String eventId, long windowStart, int windowSeconds,
                                             int partIndex, int partCount, long totalClicks,
                                             List<WebhookAggregateBuffer> buckets, boolean truncated) {
        List<Map<String, Object>> links = new ArrayList<>();
        for (WebhookAggregateBuffer bucket : buckets) {
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("device", bucket.getSampleDevice());
            sample.put("refererHost", bucket.getSampleRefererHost());
            sample.put("clickedAt", bucket.getSampleClickedAt());
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("linkId", bucket.getLinkId());
            link.put("code", bucket.getCode());
            link.put("clicks", bucket.getClicks());
            link.put("late", Boolean.TRUE.equals(bucket.getLate()));
            link.put("sample", sample);
            links.add(link);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("windowStart", windowStart);
        data.put("windowSeconds", windowSeconds);
        data.put("partIndex", partIndex);
        data.put("partCount", partCount);
        data.put("totalClicks", totalClicks);
        data.put("links", links);
        // D-C1-23: truncation is carried in the payload, never silently dropped
        if (truncated) {
            data.put("truncated", true);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", eventId);
        payload.put("type", EVENT_TYPE_DIGEST);
        payload.put("createdAt", LocalDateTime.now());
        payload.put("data", data);
        return payload;
    }

    private static int windowSeconds(WebhookEndpoint endpoint) {
        Integer seconds = endpoint.getAggregateWindowSeconds();
        return seconds != null && seconds > 0 ? seconds : AggregateWindows.DEFAULT_WINDOW_SECONDS;
    }
}
