package com.linkforge.link.support;

import com.linkforge.config.LinkForgeProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Holds the immutable TargetPolicy snapshot and hot-reloads the optional blocklist
 * file by mtime. A broken or vanished file keeps the previous snapshot (fail-safe
 * direction: stale rules beat no rules) and counts reload{result=failed}. There is
 * no HTTP management API on purpose — tenants must not edit a global list.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PhishingPolicy {

    private final LinkForgeProperties properties;
    private final MeterRegistry meterRegistry;

    private volatile TargetPolicy.Config snapshot = TargetPolicy.Config.EMPTY;
    private volatile LocalDateTime fileMtime;

    @PostConstruct
    void loadInitial() {
        TargetPolicy.Config fromProperties = new TargetPolicy.Config(
                normalize(properties.getPhishing().getBlockDomains()),
                normalize(properties.getPhishing().getWarnDomains()));
        Set<String> fileBlock = new LinkedHashSet<>();
        Set<String> fileWarn = new LinkedHashSet<>();
        if (fileConfigured() && parseFile(fromProperties, fileBlock, fileWarn) != null) {
            fileMtime = mtimeOf(Path.of(properties.getPhishing().getBlocklistFile()));
        }
        snapshot = merge(fromProperties, fileBlock, fileWarn);
        registerGauges();
        log.info("phishing policy loaded: {} block / {} warn domains", blockCount(), warnCount());
    }

    public TargetPolicy.Verdict evaluate(String url, boolean forCreate) {
        if (!properties.getPhishing().isEnabled()) {
            return TargetPolicy.Verdict.ALLOW;
        }
        return TargetPolicy.evaluate(url, snapshot, forCreate);
    }

    public int blockCount() {
        return snapshot.blockDomains().size();
    }

    public int warnCount() {
        return snapshot.warnDomains().size();
    }

    /** Reload the blocklist file now; also the scheduled entry point. Public so ITs
     * can drive it deterministically instead of sleeping a reload interval. */
    @Scheduled(fixedDelayString = "${linkforge.phishing.reload-interval-ms:30000}",
            initialDelayString = "${linkforge.phishing.reload-interval-ms:30000}")
    public void reload() {
        if (!fileConfigured()) {
            return;
        }
        Path path = Path.of(properties.getPhishing().getBlocklistFile());
        LocalDateTime mtime = mtimeOf(path);
        if (mtime != null && mtime.equals(fileMtime)) {
            meterRegistry.counter("linkforge.phishing.reload", "result", "unchanged").increment();
            return;
        }
        TargetPolicy.Config current = snapshot;
        Set<String> fileBlock = new LinkedHashSet<>();
        Set<String> fileWarn = new LinkedHashSet<>();
        TargetPolicy.Config parsed = parseFile(current, fileBlock, fileWarn);
        if (parsed == null) {
            return;
        }
        snapshot = merge(parsed, fileBlock, fileWarn);
        fileMtime = mtime;
        meterRegistry.counter("linkforge.phishing.reload", "result", "ok").increment();
        log.info("phishing blocklist file reloaded: {} block / {} warn domains", blockCount(), warnCount());
    }

    private boolean fileConfigured() {
        return properties.getPhishing().getBlocklistFile() != null
                && !properties.getPhishing().getBlocklistFile().isBlank();
    }

    /** Parses the file into the given sets; on any failure keeps the given config,
     * counts reload{result=failed} and returns null (caller keeps the old snapshot). */
    private TargetPolicy.Config parseFile(TargetPolicy.Config fromProperties,
                                          Set<String> fileBlock, Set<String> fileWarn) {
        Path path = Path.of(properties.getPhishing().getBlocklistFile());
        try {
            for (String raw : Files.readAllLines(path)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("!warn ")) {
                    fileWarn.add(TargetPolicy.normalizeEntry(line.substring("!warn ".length())));
                } else {
                    fileBlock.add(TargetPolicy.normalizeEntry(line));
                }
            }
        } catch (IOException | RuntimeException ex) {
            log.warn("phishing blocklist {} unreadable, keeping previous rules: {}",
                    path, ex.getMessage());
            meterRegistry.counter("linkforge.phishing.reload", "result", "failed").increment();
            return null;
        }
        return new TargetPolicy.Config(
                union(fromProperties.blockDomains(), fileBlock),
                union(fromProperties.warnDomains(), fileWarn));
    }

    private static LocalDateTime mtimeOf(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant().atZone(java.time.ZoneId.systemDefault())
                    .toLocalDateTime();
        } catch (IOException ex) {
            return null;
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> merged = new LinkedHashSet<>(a);
        merged.addAll(b);
        return Set.copyOf(merged);
    }

    private static TargetPolicy.Config merge(TargetPolicy.Config fromProperties,
                                             Set<String> fileBlock, Set<String> fileWarn) {
        return new TargetPolicy.Config(
                union(fromProperties.blockDomains(), fileBlock),
                union(fromProperties.warnDomains(), fileWarn));
    }

    private static Set<String> normalize(java.util.List<String> entries) {
        if (entries == null) {
            return Set.of();
        }
        return entries.stream()
                .filter(entry -> entry != null && !entry.isBlank())
                .map(TargetPolicy::normalizeEntry)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void registerGauges() {
        Gauge.builder("linkforge.phishing.rules", this, p -> p.blockCount())
                .tag("level", "block").register(meterRegistry);
        Gauge.builder("linkforge.phishing.rules", this, p -> p.warnCount())
                .tag("level", "warn").register(meterRegistry);
    }
}
