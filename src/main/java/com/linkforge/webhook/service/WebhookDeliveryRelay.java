package com.linkforge.webhook.service;

import com.linkforge.config.LinkForgeProperties;
import com.linkforge.webhook.entity.WebhookDelivery;
import com.linkforge.webhook.entity.WebhookEndpoint;
import com.linkforge.webhook.mapper.WebhookDeliveryMapper;
import com.linkforge.webhook.mapper.WebhookEndpointMapper;
import com.linkforge.webhook.support.WebhookSecretCipher;
import com.linkforge.webhook.support.WebhookSigner;
import com.linkforge.webhook.support.WebhookTargetGuard;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * B-wave2 D-Bw2-13/14: drains PENDING webhook deliveries over HTTP. Runs on the
 * shared (but now 4-thread) scheduler — the click outbox relay and the reconciler
 * are independent of a slow webhook endpoint because of the pool size, never
 * because of shared execution. 2xx = SENT; network errors, timeouts, 408/429/5xx
 * retry with min(cap, base*2^attempts) backoff; other 4xx and blocked targets fail
 * immediately; max-attempts ends in FAILED (this repo's dead-letter equivalent).
 * at-least-once: multiple relays may deliver the same row, receivers dedup on the
 * event id.
 */
@Slf4j
@Component
public class WebhookDeliveryRelay {

    private final WebhookDeliveryMapper deliveryMapper;
    private final WebhookEndpointMapper endpointMapper;
    private final WebhookSecretCipher cipher;
    private final WebhookTargetGuard targetGuard;
    private final LinkForgeProperties properties;
    private final MeterRegistry meterRegistry;
    private final HttpClient httpClient;

    public WebhookDeliveryRelay(WebhookDeliveryMapper deliveryMapper,
                                WebhookEndpointMapper endpointMapper,
                                WebhookSecretCipher cipher,
                                WebhookTargetGuard targetGuard,
                                LinkForgeProperties properties,
                                MeterRegistry meterRegistry) {
        this.deliveryMapper = deliveryMapper;
        this.endpointMapper = endpointMapper;
        this.cipher = cipher;
        this.targetGuard = targetGuard;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getWebhook().getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Scheduled(fixedDelayString = "${linkforge.webhook.relay-fixed-delay-ms:2000}",
            initialDelayString = "${linkforge.webhook.relay-initial-delay-ms:5000}")
    public void drain() {
        if (!properties.getWebhook().isEnabled() || !properties.getWebhook().isRelayEnabled()) {
            return;
        }
        List<WebhookDelivery> batch = deliveryMapper.selectPendingBatch(properties.getWebhook().getRelayBatchSize());
        for (WebhookDelivery delivery : batch) {
            try {
                deliver(delivery);
            } catch (Exception ex) {
                log.warn("webhook delivery {} failed unexpectedly: {}", delivery.getId(), ex.getMessage());
            }
        }
    }

    private void deliver(WebhookDelivery delivery) {
        WebhookEndpoint endpoint = endpointMapper.selectById(delivery.getEndpointId());
        if (endpoint == null || !Boolean.TRUE.equals(endpoint.getEnabled())) {
            deliveryMapper.markFailed(delivery.getId(), null, "endpoint disabled");
            meterRegistry.counter("linkforge.webhook.delivery", "result", "failed").increment();
            return;
        }
        if (targetGuard.isBlocked(endpoint.getUrl())) {
            deliveryMapper.markFailed(delivery.getId(), null, "blocked target");
            meterRegistry.counter("linkforge.webhook.blocked").increment();
            meterRegistry.counter("linkforge.webhook.delivery", "result", "failed").increment();
            return;
        }
        if (!cipher.isAvailable()) {
            scheduleRetry(delivery, null, "master key unavailable");
            return;
        }

        String secret;
        try {
            secret = cipher.decrypt(endpoint.getSecret());
        } catch (Exception ex) {
            scheduleRetry(delivery, null, "secret decryption failed");
            return;
        }

        String body = delivery.getPayload();
        long timestamp = System.currentTimeMillis() / 1000;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint.getUrl()))
                .timeout(Duration.ofMillis(properties.getWebhook().getRequestTimeoutMs()))
                .header("Content-Type", "application/json")
                .header(WebhookSigner.HEADER_EVENT_ID, delivery.getEventId())
                .header(WebhookSigner.HEADER_TIMESTAMP, String.valueOf(timestamp))
                .header(WebhookSigner.HEADER_SIGNATURE, WebhookSigner.sign(secret, timestamp, body))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        Integer statusCode = null;
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            statusCode = response.statusCode();
            if (statusCode >= 200 && statusCode < 300) {
                deliveryMapper.markSent(delivery.getId(), statusCode);
                meterRegistry.counter("linkforge.webhook.delivery", "result", "success").increment();
                return;
            }
            if (statusCode == 408 || statusCode == 429 || statusCode >= 500) {
                scheduleRetry(delivery, statusCode, "http " + statusCode);
            } else {
                // the receiver explicitly refused (401/403/404/410/...): retrying is pointless
                deliveryMapper.markFailed(delivery.getId(), statusCode, "http " + statusCode);
                meterRegistry.counter("linkforge.webhook.delivery", "result", "failed").increment();
            }
        } catch (IOException ex) {
            scheduleRetry(delivery, statusCode, ex.getClass().getSimpleName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            scheduleRetry(delivery, statusCode, "interrupted");
        }
    }

    private void scheduleRetry(WebhookDelivery delivery, Integer statusCode, String error) {
        int attempts = (delivery.getAttempts() == null ? 0 : delivery.getAttempts()) + 1;
        LinkForgeProperties.Webhook cfg = properties.getWebhook();
        if (attempts >= cfg.getMaxAttempts()) {
            deliveryMapper.markFailed(delivery.getId(), statusCode, error);
            meterRegistry.counter("linkforge.webhook.delivery", "result", "failed").increment();
            log.warn("webhook delivery {} -> FAILED after {} attempts ({})", delivery.getId(), attempts, error);
            return;
        }
        long backoffSeconds = Math.min(cfg.getBackoffMaxSeconds(), cfg.getBackoffBaseSeconds() * (1L << attempts));
        deliveryMapper.markRetry(delivery.getId(), LocalDateTime.now().plusSeconds(backoffSeconds), statusCode, error);
        meterRegistry.counter("linkforge.webhook.delivery", "result", "retry").increment();
    }
}
