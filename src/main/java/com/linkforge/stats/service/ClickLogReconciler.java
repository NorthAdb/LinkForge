package com.linkforge.stats.service;

import com.linkforge.config.LinkForgeProperties;
import com.linkforge.stats.entity.ClickLog;
import com.linkforge.stats.mapper.ClickLogMapper;
import com.linkforge.stats.mq.ClickEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Safety net for sync-written rows not yet consumed (A7: live delivery belongs to
 * ClickOutboxRelay). Replay uses the same deterministic "log-{id}" eventIds as the
 * outbox path, so rows already counted are skipped and each click counts exactly
 * once. With the sync write off this loop finds nothing — detail rows then come from
 * the consumer itself and the outbox relay is the catch-up path.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClickLogReconciler {

    private final ClickLogMapper clickLogMapper;
    private final ClickStatsService clickStatsService;
    private final LinkForgeProperties properties;

    @Scheduled(fixedDelayString = "${linkforge.mq.reconcile-fixed-delay-ms:30000}",
            initialDelayString = "${linkforge.mq.reconcile-initial-delay-ms:15000}")
    public void reconcile() {
        if (!properties.getMq().isAsyncClickEnabled()) {
            return;
        }
        try {
            List<ClickLog> pending = clickLogMapper.selectUnconsumed(500);
            for (ClickLog row : pending) {
                clickStatsService.consume(new ClickEvent(
                        "log-" + row.getId(), row.getTenantId(), row.getLinkId(), row.getCode(),
                        null, row.getIp(), row.getUserAgent(), row.getReferer(), row.getClickedAt()));
            }
            if (!pending.isEmpty()) {
                log.info("reconciled {} click rows into stats", pending.size());
            }
        } catch (Exception ex) {
            log.warn("click reconcile pass failed: {}", ex.getMessage());
        }
    }
}
