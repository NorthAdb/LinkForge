package com.linkforge.stats.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.stats.entity.Outbox;
import com.linkforge.stats.mapper.OutboxMapper;
import com.linkforge.stats.mq.ClickEvent;
import com.linkforge.stats.mq.ClickEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * A7 relay: drains PENDING outbox rows to RabbitMQ. A failed publish keeps the row
 * PENDING with a growing backoff, so a broker outage never loses clicks and the
 * redirect path is never blocked. Flags are read per tick so drills and tests can
 * toggle them at runtime. Rows exceeding relay-max-attempts are marked FAILED
 * (kept for inspection; replay is a SQL UPDATE back to PENDING).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClickOutboxRelay {

    private static final long MAX_BACKOFF_SECONDS = 60;

    private final OutboxMapper outboxMapper;
    private final ClickEventPublisher clickEventPublisher;
    private final ObjectMapper objectMapper;
    private final LinkForgeProperties properties;
    private final MeterRegistry meterRegistry;

    @Scheduled(fixedDelayString = "${linkforge.mq.relay-fixed-delay-ms:2000}",
            initialDelayString = "${linkforge.mq.relay-initial-delay-ms:3000}")
    public void relay() {
        LinkForgeProperties.Mq mq = properties.getMq();
        if (!mq.isRelayEnabled() || !mq.isAsyncClickEnabled()) {
            return;
        }
        try {
            List<Outbox> batch = outboxMapper.selectPendingBatch(mq.getRelayBatchSize());
            int sent = 0;
            for (Outbox row : batch) {
                if (row.getAttempts() != null && row.getAttempts() >= mq.getRelayMaxAttempts()) {
                    outboxMapper.markFailed(row.getId());
                    meterRegistry.counter("linkforge.outbox.failed").increment();
                    log.error("outbox event={} exceeded {} attempts, marked FAILED",
                            row.getEventId(), mq.getRelayMaxAttempts());
                    continue;
                }
                try {
                    ClickEvent event = objectMapper.readValue(row.getPayload(), ClickEvent.class);
                    clickEventPublisher.publish(event);
                    outboxMapper.markSent(row.getId());
                    sent++;
                } catch (Exception ex) {
                    outboxMapper.markRetry(row.getId(), backoffSeconds(row.getAttempts()));
                    log.warn("outbox relay deferred event={} attempt={}: {}",
                            row.getEventId(), row.getAttempts() + 1, ex.getMessage());
                }
            }
            if (sent > 0) {
                log.info("outbox relay delivered {} events", sent);
            }
        } catch (Exception ex) {
            log.warn("outbox relay pass failed: {}", ex.getMessage());
        }
    }

    private static long backoffSeconds(Integer attempts) {
        int n = attempts == null ? 0 : attempts;
        return Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(n, 6));
    }
}
