package com.linkforge.link.service;

import com.linkforge.config.LinkForgeProperties;
import com.linkforge.link.mapper.MaliciousHostMapper;
import com.linkforge.link.support.MaliciousFeedParser;
import com.linkforge.link.support.MaliciousHostSnapshot;
import com.linkforge.link.support.PhishingPolicy;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Batch 3b: imports the malicious-host feed (URLhaus hostfile) into
 * {@code lf_malicious_host} on a schedule. Fetch and ingest are separate on
 * purpose: the scheduled path fetches over HTTP, tests drive {@link #ingest}
 * with fixture text — no test ever calls out. A failed fetch/parse keeps the
 * old table and the old snapshot (fail-open toward the last good data) and
 * only moves the sync counter; the {@code last_success} gauge is the
 * freshness signal. Hosts that vanish from the feed are never deleted.
 */
@Slf4j
@Component
public class MaliciousHostSyncService {

    public static final String SOURCE_URLHAUS = "urlhaus";
    private static final int UPSERT_BATCH = 500;

    private final MaliciousHostMapper mapper;
    private final MaliciousHostSnapshot snapshot;
    private final PhishingPolicy phishingPolicy;
    private final LinkForgeProperties properties;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate tx;
    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong(0);

    public MaliciousHostSyncService(MaliciousHostMapper mapper,
                                    MaliciousHostSnapshot snapshot,
                                    PhishingPolicy phishingPolicy,
                                    LinkForgeProperties properties,
                                    MeterRegistry meterRegistry,
                                    PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.snapshot = snapshot;
        this.phishingPolicy = phishingPolicy;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    void registerGauges() {
        Gauge.builder("linkforge.malicious.feed.last_success", lastSuccessEpochSeconds, AtomicLong::get)
                .description("epoch seconds of the last successful feed ingest; 0 = never in this process")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${linkforge.malicious-feed.interval-ms:21600000}",
            initialDelayString = "${linkforge.malicious-feed.initial-delay-ms:60000}")
    public void scheduledSync() {
        if (!properties.getMaliciousFeed().isEnabled()) {
            return;
        }
        try {
            int ingested = syncOnce();
            log.info("malicious host feed sync ok: {} hosts in feed, {} in table",
                    ingested, snapshot.size());
        } catch (Exception ex) {
            meterRegistry.counter("linkforge.malicious.feed.sync", "result", "failed").increment();
            log.warn("malicious host feed sync failed, keeping previous data: {}", ex.getMessage());
        }
    }

    /** One fetch + ingest round; throws on fetch failure so the caller can count it. */
    public int syncOnce() throws Exception {
        return ingest(fetch());
    }

    /** The only outbound call in the feature; isolated so tests never touch it. */
    protected String fetch() throws Exception {
        String url = properties.getMaliciousFeed().getUrl();
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "LinkForge malicious-feed sync")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("feed answered HTTP " + response.statusCode());
        }
        return response.body();
    }

    /**
     * Parses feed text and upserts every host in one transaction, then refreshes
     * the verdict snapshot. Returns how many distinct hosts the feed carried.
     * Public so ITs can drive it deterministically with fixture text.
     */
    public int ingest(String feedText) {
        Set<String> hosts = MaliciousFeedParser.parse(feedText);
        LocalDateTime now = LocalDateTime.now();
        List<String> batch = new ArrayList<>(UPSERT_BATCH);
        tx.executeWithoutResult(status -> {
            for (String host : hosts) {
                batch.add(host);
                if (batch.size() == UPSERT_BATCH) {
                    mapper.upsertBatch(batch, SOURCE_URLHAUS, now);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                mapper.upsertBatch(batch, SOURCE_URLHAUS, now);
            }
        });
        lastSuccessEpochSeconds.set(Instant.now().getEpochSecond());
        meterRegistry.counter("linkforge.malicious.feed.sync", "result", "ok").increment();
        snapshot.refresh();
        phishingPolicy.refreshFeedHosts();
        return hosts.size();
    }
}
