package com.linkforge.retention;

import com.linkforge.config.LinkForgeProperties;
import com.linkforge.stats.mapper.OutboxMapper;
import com.linkforge.webhook.mapper.WebhookDeliveryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Deletes terminal SENT rows from {@code lf_outbox} and {@code lf_webhook_delivery}
 * once they are older than {@code linkforge.retention.sent-days}
 * ({@code LINKFORGE_SENT_RETENTION_DAYS}, default 7). {@code <= 0} disables the job.
 *
 * <p>Idempotent: each tick issues bounded {@code DELETE ... WHERE status = 'SENT'},
 * so a repeat pass deletes nothing that is already gone and never touches PENDING,
 * FAILED, or in-flight rows (those are not SENT). Shares the existing scheduler
 * pool; the default period is one hour, so it does not sit on a relay thread.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SentRowCleanup {

    private static final long SECONDS_PER_DAY = 86_400L;

    private final OutboxMapper outboxMapper;
    private final WebhookDeliveryMapper deliveryMapper;
    private final LinkForgeProperties properties;

    @Scheduled(fixedDelayString = "${linkforge.retention.cleanup-interval-ms:3600000}",
            initialDelayString = "${linkforge.retention.cleanup-initial-delay-ms:60000}")
    public void cleanup() {
        try {
            int removed = purgeSent();
            if (removed > 0) {
                log.info("sent-row cleanup removed {} SENT rows older than {} days",
                        removed, properties.getRetention().getSentDays());
            }
        } catch (Exception ex) {
            log.warn("sent-row cleanup pass failed: {}", ex.getMessage());
        }
    }

    /**
     * One retention pass. Outbox cutoff uses the DB clock (seconds before {@code NOW()});
     * webhook cutoff uses the JVM clock, matching how each {@code sent_at} is written.
     */
    public int purgeSent() {
        LinkForgeProperties.Retention cfg = properties.getRetention();
        int days = cfg.getSentDays();
        if (days <= 0) {
            return 0;
        }
        int batch = Math.max(1, cfg.getCleanupBatchSize());
        int maxPasses = Math.max(1, cfg.getCleanupMaxPasses());
        long retentionSeconds = days * SECONDS_PER_DAY;
        LocalDateTime webhookCutoff = LocalDateTime.now().minusDays(days);
        int total = 0;
        for (int pass = 0; pass < maxPasses; pass++) {
            int outbox = outboxMapper.deleteSentOlderThan(retentionSeconds, batch);
            int webhook = deliveryMapper.deleteSentOlderThan(webhookCutoff, batch);
            total += outbox + webhook;
            if (outbox < batch && webhook < batch) {
                break;
            }
        }
        return total;
    }
}
