package com.linkforge.observability;

import com.linkforge.config.LinkForgeProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * B1: outbox/webhook backlog gauges, sampled off the scrape path (a COUNT inside a
 * scrape would couple Prometheus latency to the DB and amplify with concurrent
 * scrapes). Sampling failures keep the last value and WARN.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BacklogGauges {

    private final JdbcTemplate jdbc;
    private final MeterRegistry meterRegistry;
    private final LinkForgeProperties properties;

    private final AtomicLong outboxPending = new AtomicLong();
    private final AtomicLong webhookPending = new AtomicLong();
    private final AtomicLong webhookFailed = new AtomicLong();

    @PostConstruct
    void register() {
        Gauge.builder("linkforge.outbox.pending", outboxPending, AtomicLong::get).register(meterRegistry);
        Gauge.builder("linkforge.webhook.pending", webhookPending, AtomicLong::get).register(meterRegistry);
        Gauge.builder("linkforge.webhook.failed", webhookFailed, AtomicLong::get).register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${linkforge.metrics.backlog-sample-interval-ms:15000}",
            initialDelayString = "${linkforge.metrics.backlog-sample-interval-ms:15000}")
    public void sample() {
        sample(outboxPending, "SELECT COUNT(*) FROM lf_outbox WHERE status = 'PENDING'");
        sample(webhookPending, "SELECT COUNT(*) FROM lf_webhook_delivery WHERE status = 'PENDING'");
        sample(webhookFailed, "SELECT COUNT(*) FROM lf_webhook_delivery WHERE status = 'FAILED'");
    }

    private void sample(AtomicLong target, String sql) {
        try {
            Long count = jdbc.queryForObject(sql, Long.class);
            if (count != null) {
                target.set(count);
            }
        } catch (Exception ex) {
            log.warn("backlog sample failed: {}", ex.getMessage());
        }
    }
}
