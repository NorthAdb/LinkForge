package com.linkforge.link.support;

import com.linkforge.config.LinkForgeProperties;
import com.linkforge.link.mapper.MaliciousHostMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * Batch 3b: in-memory snapshot of {@code lf_malicious_host}. Verdicts read this
 * set (through {@link PhishingPolicy}'s merged config), never the table, so
 * the redirect/create hot paths gain no DB read. When the feed switch is off
 * the snapshot is pinned empty — rows an earlier enablement left in the table
 * have zero effect, byte-for-byte the pre-3b behavior.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaliciousHostSnapshot {

    private final MaliciousHostMapper mapper;
    private final LinkForgeProperties properties;
    private final MeterRegistry meterRegistry;

    private volatile Set<String> hosts = Set.of();

    @PostConstruct
    void init() {
        Gauge.builder("linkforge.malicious.feed.hosts", this, MaliciousHostSnapshot::size)
                .register(meterRegistry);
        refresh();
    }

    /** Reloads the whole table (feed scale is tens of thousands of short
     * strings — a full copy is cheaper than change tracking). A failed read
     * keeps the previous snapshot: stale rules beat no rules.
     *
     * @return true when the in-memory snapshot now reflects the table (or
     *         the feed is disabled and the snapshot is pinned empty);
     *         false when the read failed and the previous snapshot is
     *         still in force — callers that track freshness (the sync
     *         service's last-success gauge) must not claim an apply that
     *         did not happen (S/N wave, audit S3). */
    public boolean refresh() {
        if (!properties.getMaliciousFeed().isEnabled()) {
            hosts = Set.of();
            return true;
        }
        try {
            hosts = Set.copyOf(new HashSet<>(mapper.selectAllHosts()));
            log.info("malicious host snapshot refreshed: {} hosts", hosts.size());
            return true;
        } catch (Exception ex) {
            log.warn("malicious host snapshot refresh failed, keeping {} hosts: {}",
                    hosts.size(), ex.getMessage());
            return false;
        }
    }

    public Set<String> hosts() {
        return hosts;
    }

    public int size() {
        return hosts.size();
    }
}
