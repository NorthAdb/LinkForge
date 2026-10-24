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
import com.linkforge.webhook.mapper.WebhookDeliveryMapper;
import com.linkforge.webhook.mapper.WebhookEndpointMapper;
import com.linkforge.webhook.support.WebhookSecretCipher;
import com.linkforge.webhook.support.WebhookTargetGuard;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Webhook endpoint management (JWT only, one endpoint per tenant) and the consume
 * hook that turns a freshly consumed click into a PENDING delivery row — inside
 * the dedup transaction, so every click eventId yields at most one delivery no
 * matter how often MQ or the reconciler replays it (D-Bw2-9/12).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    static final String EVENT_TYPE = "link.clicked";

    private final WebhookEndpointMapper endpointMapper;
    private final WebhookDeliveryMapper deliveryMapper;
    private final WebhookSecretCipher cipher;
    private final WebhookTargetGuard targetGuard;
    private final LinkForgeProperties properties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    // ---- endpoint management -------------------------------------------------

    /** Creates (secret returned in plaintext ONCE) or updates url/enabled (secret unchanged). */
    public Map<String, Object> upsert(String url, Boolean enabled, AuthUser user) {
        requireWebhookAvailable();
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
            endpoint.setCreatedBy(user.getUserId());
            endpoint.setCreatedAt(LocalDateTime.now());
            endpoint.setUpdatedAt(LocalDateTime.now());
            endpointMapper.insert(endpoint);
        } else {
            endpoint.setUrl(url);
            if (enabled != null) {
                endpoint.setEnabled(enabled);
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
        endpointMapper.deleteById(endpoint.getId());
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

    private static Map<String, Object> toView(WebhookEndpoint endpoint) {
        String secret = endpoint.getSecret();
        String masked = secret != null && secret.length() > 8
                ? secret.substring(0, 7) + "****" : "****";
        return new java.util.HashMap<>(Map.of(
                "url", endpoint.getUrl(),
                "enabled", Boolean.TRUE.equals(endpoint.getEnabled()),
                "secretMasked", masked,
                "createdAt", endpoint.getCreatedAt(),
                "updatedAt", endpoint.getUpdatedAt()));
    }

    // ---- consume hook (D-Bw2-9) ----------------------------------------------

    /**
     * Called from ClickStatsService.consume INSIDE the dedup transaction, after the
     * lf_consumed_event insert won. Every failure mode is swallowed: statistics must
     * never break because webhook plumbing is down. Duplicate event ids cannot
     * normally occur (the dedup gate is upstream) but the UK backstops anyway.
     */
    public void recordClickDelivery(ClickEvent event) {
        try {
            if (!properties.getWebhook().isEnabled() || !cipher.isAvailable()) {
                return;
            }
            WebhookEndpoint endpoint = endpointMapper.selectOne(new LambdaQueryWrapper<WebhookEndpoint>()
                    .eq(WebhookEndpoint::getTenantId, event.getTenantId())
                    .eq(WebhookEndpoint::getEnabled, true));
            if (endpoint == null) {
                return;
            }
            String eventId = "evt_" + event.getEventId();
            List<ClickDimensions.Dimension> dims = ClickDimensions.of(event.getUserAgent(), event.getReferer());
            String device = dims.stream().filter(d -> ClickDimensions.TYPE_DEVICE.equals(d.type()))
                    .findFirst().map(ClickDimensions.Dimension::value).orElse("unknown");
            String refererHost = dims.stream().filter(d -> ClickDimensions.TYPE_REFERER_HOST.equals(d.type()))
                    .findFirst().map(ClickDimensions.Dimension::value).orElse("direct");
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
        } catch (Exception ex) {
            log.warn("webhook delivery enqueue failed for event {}: {}", event.getEventId(), ex.getMessage());
        }
    }
}
