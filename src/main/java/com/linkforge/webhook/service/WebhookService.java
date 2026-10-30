package com.linkforge.webhook.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.common.exception.BizException;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.security.AuthUser;
import com.linkforge.stats.mq.ClickEvent;
import com.linkforge.stats.support.ClickDimensions;
import com.linkforge.webhook.entity.WebhookDelivery;
import com.linkforge.webhook.entity.WebhookEndpoint;
import com.linkforge.webhook.mapper.WebhookAggregateBufferMapper;
import com.linkforge.webhook.mapper.WebhookDeliveryMapper;
import com.linkforge.webhook.mapper.WebhookEndpointMapper;
import com.linkforge.webhook.support.AggregateWindows;
import com.linkforge.webhook.support.WebhookSecretCipher;
import com.linkforge.webhook.support.WebhookTargetGuard;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Webhook endpoint management (JWT only, one endpoint per tenant) and the consume
 * hook that turns a freshly consumed click into either a PENDING per-click delivery
 * row or, in AGGREGATE mode (D-C1-18..25), a buffer bucket + possible threshold
 * flush — inside the dedup transaction, so every click eventId yields at most one
 * enqueue no matter how often MQ or the reconciler replays it (D-Bw2-9/12). Enqueue
 * is cipher-independent (S1-b); insert failures roll the consume transaction back
 * so statistics and delivery intent stay atomic (S1-d).
 *
 * Mode switching (D-C1-25): PER_CLICK|AGGREGATE, window 15..300 s, maxEvents
 * 1..100000, absent fields keep their values; switching AGGREGATE -> PER_CLICK
 * flushes ALL open windows BEFORE the update is saved; the secret never rotates on
 * a mode change. Deleting an endpoint with buffered windows inserts digest
 * deliveries with status FAILED and lastError 'endpoint deleted with pending
 * aggregate buffer' (intent preserved, never sent), deletes the buffer rows, then
 * deletes the endpoint.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    static final String EVENT_TYPE = "link.clicked";
    /** D-C1-19 bounds and defaults for the AGGREGATE knobs. */
    static final int MIN_WINDOW_SECONDS = 15;
    static final int MAX_WINDOW_SECONDS = 300;
    static final int DEFAULT_MAX_EVENTS = 100;
    static final int MIN_MAX_EVENTS = 1;
    static final int MAX_MAX_EVENTS = 100_000;

    private final WebhookEndpointMapper endpointMapper;
    private final WebhookDeliveryMapper deliveryMapper;
    private final WebhookAggregateBufferMapper bufferMapper;
    private final WebhookAggregateFlusher aggregateFlusher;
    private final WebhookSecretCipher cipher;
    private final WebhookTargetGuard targetGuard;
    private final LinkForgeProperties properties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    // ---- endpoint management -------------------------------------------------

    /**
     * Creates (secret returned in plaintext ONCE) or updates url/enabled plus the
     * AGGREGATE knobs (secret unchanged, D-C1-25).
     */
    public Map<String, Object> upsert(Map<String, Object> request, AuthUser user) {
        String url = request.get("url") == null ? null : String.valueOf(request.get("url"));
        Object enabledRaw = request.get("enabled");
        Boolean enabled = enabledRaw == null ? null : Boolean.valueOf(String.valueOf(enabledRaw));
        if (url == null || url.isBlank()) {
            throw new BizException("url is required");
        }
        requireWebhookAvailable();
        String deliveryMode = parseDeliveryMode(request.get("deliveryMode"));
        Integer windowSeconds = parseBounded(request.get("aggregateWindowSeconds"),
                MIN_WINDOW_SECONDS, MAX_WINDOW_SECONDS, "aggregateWindowSeconds must be 15..300");
        Integer maxEvents = parseBounded(request.get("aggregateMaxEvents"),
                MIN_MAX_EVENTS, MAX_MAX_EVENTS, "aggregateMaxEvents must be 1..100000");
        targetGuard.check(url);
        WebhookEndpoint endpoint = endpointMapper.selectOne(
                new LambdaQueryWrapper<WebhookEndpoint>().eq(WebhookEndpoint::getTenantId, user.getTenantId()));
        boolean created = endpoint == null;
        String plaintextSecret = null;
        if (created) {
            endpoint = new WebhookEndpoint();
            endpoint.setTenantId(user.getTenantId());
            endpoint.setUrl(url);
            plaintextSecret = WebhookSecretCipher.generateSecret();
            endpoint.setSecret(cipher.encrypt(plaintextSecret));
            endpoint.setEnabled(enabled != null ? enabled : Boolean.TRUE);
            endpoint.setDeliveryMode(deliveryMode != null ? deliveryMode : WebhookEndpoint.MODE_PER_CLICK);
            endpoint.setAggregateWindowSeconds(windowSeconds != null ? windowSeconds : AggregateWindows.DEFAULT_WINDOW_SECONDS);
            endpoint.setAggregateMaxEvents(maxEvents != null ? maxEvents : DEFAULT_MAX_EVENTS);
            endpoint.setCreatedBy(user.getUserId());
            endpoint.setCreatedAt(LocalDateTime.now());
            endpoint.setUpdatedAt(LocalDateTime.now());
            endpointMapper.insert(endpoint);
        } else {
            // D-C1-25: flush what the aggregate buffer still holds BEFORE the mode flips
            if (WebhookEndpoint.MODE_PER_CLICK.equals(deliveryMode)
                    && WebhookEndpoint.MODE_AGGREGATE.equals(endpoint.getDeliveryMode())) {
                aggregateFlusher.flushAllOpenWindows(endpoint);
            }
            endpoint.setUrl(url);
            if (enabled != null) {
                endpoint.setEnabled(enabled);
            }
            if (deliveryMode != null) {
                endpoint.setDeliveryMode(deliveryMode);
            }
            if (windowSeconds != null) {
                endpoint.setAggregateWindowSeconds(windowSeconds);
            }
            if (maxEvents != null) {
                endpoint.setAggregateMaxEvents(maxEvents);
            }
            endpoint.setUpdatedAt(LocalDateTime.now());
            endpointMapper.updateById(endpoint);
        }
        Map<String, Object> view = toView(endpoint);
        if (created) {
            view.put("secret", plaintextSecret); // only ever here and in rotate
        }
        return view;
    }

    public Map<String, Object> get(AuthUser user) {
        WebhookEndpoint endpoint = requireEndpoint(user);
        return toView(endpoint);
    }

    public Map<String, Object> rotateSecret(AuthUser user) {
        requireWebhookAvailable();
        WebhookEndpoint endpoint = requireEndpoint(user);
        String plaintextSecret = WebhookSecretCipher.generateSecret();
        endpoint.setSecret(cipher.encrypt(plaintextSecret));
        endpoint.setUpdatedAt(LocalDateTime.now());
        endpointMapper.updateById(endpoint);
        Map<String, Object> view = toView(endpoint);
        view.put("secret", plaintextSecret);
        return view;
    }

    public void delete(AuthUser user) {
        WebhookEndpoint endpoint = requireEndpoint(user);
        // D-C1-25: buffered windows die as FAILED digest intents, never as silence
        aggregateFlusher.discardBufferedOnDelete(endpoint);
        endpointMapper.deleteById(endpoint.getId());
    }

    private static String parseDeliveryMode(Object raw) {
        if (raw == null) {
            return null; // absent = keep existing / default
        }
        String value = String.valueOf(raw);
        if (!WebhookEndpoint.MODE_PER_CLICK.equals(value) && !WebhookEndpoint.MODE_AGGREGATE.equals(value)) {
            throw new BizException(400, "deliveryMode must be PER_CLICK or AGGREGATE");
        }
        return value;
    }

    private static Integer parseBounded(Object raw, int min, int max, String message) {
        if (raw == null) {
            return null;
        }
        int value;
        try {
            value = raw instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException ex) {
            throw new BizException(400, message);
        }
        if (value < min || value > max) {
            throw new BizException(400, message);
        }
        return value;
    }

    private WebhookEndpoint requireEndpoint(AuthUser user) {
        WebhookEndpoint endpoint = endpointMapper.selectOne(
                new LambdaQueryWrapper<WebhookEndpoint>().eq(WebhookEndpoint::getTenantId, user.getTenantId()));
        if (endpoint == null) {
            throw new BizException(404, "webhook endpoint not configured");
        }
        return endpoint;
    }

    private void requireWebhookAvailable() {
        if (!properties.getWebhook().isEnabled() || !cipher.isAvailable()) {
            throw new BizException(503, "webhook not available: feature disabled or master key not configured");
        }
    }

    private Map<String, Object> toView(WebhookEndpoint endpoint) {
        String secret = endpoint.getSecret();
        String masked = secret != null && secret.length() > 8
                ? secret.substring(0, 7) + "****" : "****";
        Map<String, Object> view = new java.util.HashMap<>(Map.of(
                "url", endpoint.getUrl(),
                "enabled", Boolean.TRUE.equals(endpoint.getEnabled()),
                "secretMasked", masked,
                "createdAt", endpoint.getCreatedAt(),
                "updatedAt", endpoint.getUpdatedAt()));
        // D-C1-34: mode knobs plus the newest delivery row, whatever its state
        view.put("deliveryMode", endpoint.getDeliveryMode());
        view.put("aggregateWindowSeconds", endpoint.getAggregateWindowSeconds());
        view.put("aggregateMaxEvents", endpoint.getAggregateMaxEvents());
        view.put("lastDelivery", toLastDeliveryView(deliveryMapper.selectNewestByEndpoint(endpoint.getId())));
        return view;
    }

    private static Map<String, Object> toLastDeliveryView(WebhookDelivery last) {
        if (last == null) {
            return null;
        }
        Map<String, Object> view = new java.util.LinkedHashMap<>();
        view.put("eventId", last.getEventId());
        view.put("eventType", last.getEventType());
        view.put("status", last.getStatus());
        view.put("createdAt", last.getCreatedAt());
        view.put("sentAt", last.getSentAt());
        view.put("attempts", last.getAttempts());
        return view;
    }

    // ---- consume hook (D-Bw2-9) ----------------------------------------------

    /**
     * Called from ClickStatsService.consume INSIDE the dedup transaction, after the
     * lf_consumed_event insert won. Enqueue depends ONLY on the endpoint existing
     * and being enabled — the secret is needed at send time, not here, so a missing
     * master key still records the delivery intent as PENDING (S1-b; the relay
     * skips batches until the key is configured, without consuming attempts).
     *
     * Insert failures ROLL BACK the consume transaction (S1-d, 萧北 confirmed):
     * statistics and delivery intent commit or die together (D-Bw2-9 ③), the MQ
     * semantics then handle the replay/DLQ. The only exception is a duplicate
     * (endpoint_id, event_id) — already enqueued by a racing path, count and move on.
     */
    public void recordClickDelivery(ClickEvent event) {
        boolean featureOff = !properties.getWebhook().isEnabled();
        if (featureOff) {
            // S1-a: clicks during an explicitly disabled feature are not back-delivered (README)
            return;
        }
        try {
            WebhookEndpoint endpoint = endpointMapper.selectOne(new LambdaQueryWrapper<WebhookEndpoint>()
                    .eq(WebhookEndpoint::getTenantId, event.getTenantId())
                    .eq(WebhookEndpoint::getEnabled, true));
            if (endpoint == null) {
                return;
            }
            List<ClickDimensions.Dimension> dims = ClickDimensions.of(event.getUserAgent(), event.getReferer());
            String device = dims.stream().filter(d -> ClickDimensions.TYPE_DEVICE.equals(d.type()))
                    .findFirst().map(ClickDimensions.Dimension::value).orElse("unknown");
            String refererHost = dims.stream().filter(d -> ClickDimensions.TYPE_REFERER_HOST.equals(d.type()))
                    .findFirst().map(ClickDimensions.Dimension::value).orElse("direct");
            if (WebhookEndpoint.MODE_AGGREGATE.equals(endpoint.getDeliveryMode())) {
                bufferAggregateClick(endpoint, event, device, refererHost);
            } else {
                enqueuePerClick(endpoint, event, device, refererHost);
            }
        } catch (DuplicateKeyException ex) {
            meterRegistry.counter("linkforge.webhook.enqueue.failed", "reason", "duplicate").increment();
        } catch (Exception ex) {
            meterRegistry.counter("linkforge.webhook.enqueue.failed", "reason", ex.getClass().getSimpleName()).increment();
            log.warn("webhook delivery enqueue failed for event {}: {}", event.getEventId(), ex.getMessage());
            throw ex instanceof RuntimeException runtime ? runtime : new IllegalStateException(ex);
        }
    }

    /** The PER_CLICK contract — byte-identical to pre-aggregate behavior. */
    private void enqueuePerClick(WebhookEndpoint endpoint, ClickEvent event, String device, String refererHost)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        String eventId = "evt_" + event.getEventId();
        String payload = objectMapper.writeValueAsString(Map.of(
                "id", eventId,
                "type", EVENT_TYPE,
                "createdAt", LocalDateTime.now(),
                "data", Map.of(
                        "linkId", event.getLinkId(),
                        "code", event.getCode(),
                        "clickedAt", event.getClickedAt() != null ? event.getClickedAt() : LocalDateTime.now(),
                        "device", device,
                        "refererHost", refererHost)));

        WebhookDelivery delivery = new WebhookDelivery();
        delivery.setTenantId(event.getTenantId());
        delivery.setEndpointId(endpoint.getId());
        delivery.setEventId(eventId);
        delivery.setEventType(EVENT_TYPE);
        delivery.setPayload(payload);
        delivery.setStatus(WebhookDelivery.STATUS_PENDING);
        delivery.setAttempts(0);
        delivery.setCreatedAt(LocalDateTime.now());
        deliveryMapper.insert(delivery);
        meterRegistry.counter("linkforge.webhook.enqueued").increment();
    }

    /**
     * D-C1-18/24: no per-click row — the click lands in its (endpoint, window,
     * link) bucket (late clicks join the open window, flagged), the shared enqueued
     * counter ticks (D-C1-26), and a full window seals inline (D-C1-21) in the
     * transaction the consume already holds.
     */
    private void bufferAggregateClick(WebhookEndpoint endpoint, ClickEvent event, String device, String refererHost) {
        long nowEpoch = System.currentTimeMillis() / 1000;
        AggregateWindows.Slot slot = AggregateWindows.slotOf(event.getClickedAt(),
                endpoint.getAggregateWindowSeconds(), nowEpoch);
        bufferMapper.upsertClick(event.getTenantId(), endpoint.getId(), slot.windowStart(),
                event.getLinkId(), event.getCode(), slot.late(), device, refererHost,
                event.getClickedAt() != null ? event.getClickedAt() : LocalDateTime.now());
        meterRegistry.counter("linkforge.webhook.enqueued").increment();
        Integer maxEvents = endpoint.getAggregateMaxEvents();
        long sealedAt = maxEvents != null && maxEvents > 0 ? maxEvents : DEFAULT_MAX_EVENTS;
        if (bufferMapper.sumClicks(endpoint.getId(), slot.windowStart()) >= sealedAt) {
            aggregateFlusher.flushWindow(endpoint, slot.windowStart());
        }
    }
}
