package com.linkforge.stats.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linkforge.common.result.PageResponse;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.link.entity.LinkVariant;
import com.linkforge.link.entity.ShortLink;
import com.linkforge.link.mapper.LinkVariantMapper;
import com.linkforge.link.mapper.ShortLinkMapper;
import com.linkforge.link.support.CsvFormat;
import com.linkforge.link.support.MatchRule;
import com.linkforge.stats.dto.AbReportResponse;
import com.linkforge.stats.dto.ClickDetail;
import com.linkforge.stats.dto.DailyClickCount;
import com.linkforge.stats.dto.DailyClicks;
import com.linkforge.stats.dto.DailyUv;
import com.linkforge.stats.dto.DimBreakdown;
import com.linkforge.stats.dto.LinkStatsResponse;
import com.linkforge.stats.entity.ClickLog;
import com.linkforge.stats.entity.ConsumedEvent;
import com.linkforge.stats.mapper.ClickDailyMapper;
import com.linkforge.stats.mapper.ClickDimDailyMapper;
import com.linkforge.stats.mapper.ClickLogMapper;
import com.linkforge.stats.mapper.ConsumedEventMapper;
import com.linkforge.stats.mq.ClickEvent;
import com.linkforge.stats.support.AbReport;
import com.linkforge.stats.support.ClickDimensions;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class ClickStatsService {

    private final ConsumedEventMapper consumedEventMapper;
    private final ClickDailyMapper clickDailyMapper;
    private final ClickDimDailyMapper clickDimDailyMapper;
    private final ClickLogMapper clickLogMapper;
    private final LinkVariantMapper linkVariantMapper;
    private final ShortLinkMapper shortLinkMapper;
    private final LinkForgeProperties properties;
    private final com.linkforge.webhook.service.WebhookService webhookService;
    private final TransactionTemplate dedupTx;
    private final TransactionTemplate snapshotTx;

    public ClickStatsService(ConsumedEventMapper consumedEventMapper,
                             ClickDailyMapper clickDailyMapper,
                             ClickDimDailyMapper clickDimDailyMapper,
                             ClickLogMapper clickLogMapper,
                             LinkVariantMapper linkVariantMapper,
                             ShortLinkMapper shortLinkMapper,
                             LinkForgeProperties properties,
                             com.linkforge.webhook.service.WebhookService webhookService,
                             PlatformTransactionManager transactionManager) {
        this.consumedEventMapper = consumedEventMapper;
        this.clickDailyMapper = clickDailyMapper;
        this.clickDimDailyMapper = clickDimDailyMapper;
        this.clickLogMapper = clickLogMapper;
        this.linkVariantMapper = linkVariantMapper;
        this.shortLinkMapper = shortLinkMapper;
        this.properties = properties;
        this.webhookService = webhookService;
        this.dedupTx = new TransactionTemplate(transactionManager);
        // S/N wave (audit S4-1): a SEPARATE read-only RR template for the
        // stats snapshot reads — never the write-side dedupTx.
        this.snapshotTx = new TransactionTemplate(transactionManager);
        this.snapshotTx.setReadOnly(true);
        this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /**
     * Idempotent consume. Dedup insert, detail backfill and daily increment share one
     * short transaction, so a crash between them can neither double count nor silently
     * drop a click. A duplicate key inside the callback is a plain skip: MySQL rolls
     * back only the failed statement.
     *
     * A7: "log-{id}" events already carry a sync-written lf_click_log row; events with
     * a UUID eventId (sync write off) get their detail row here, so detail/UV degrade
     * to eventual consistency instead of disappearing.
     */
    public boolean consume(ClickEvent event) {
        LocalDate date = event.getClickedAt() != null
                ? event.getClickedAt().toLocalDate()
                : LocalDate.now();
        return Boolean.TRUE.equals(dedupTx.execute(status -> {
            try {
                ConsumedEvent row = new ConsumedEvent();
                row.setEventId(event.getEventId());
                row.setConsumedAt(LocalDateTime.now());
                consumedEventMapper.insert(row);
            } catch (DuplicateKeyException ex) {
                return false;
            }
            if (!event.getEventId().startsWith("log-")) {
                ClickLog detail = new ClickLog();
                detail.setTenantId(event.getTenantId());
                detail.setLinkId(event.getLinkId());
                detail.setCode(event.getCode());
                detail.setIp(event.getIp());
                detail.setUserAgent(event.getUserAgent());
                detail.setReferer(event.getReferer());
                detail.setClickedAt(event.getClickedAt() != null ? event.getClickedAt() : LocalDateTime.now());
                detail.setVariant(event.getVariant());
                // marker: this row is already counted under the UUID eventId, so the
                // reconciler (which replays implicit log-{id} rows) must skip it
                detail.setEventId(event.getEventId());
                clickLogMapper.insert(detail);
            }
            clickDailyMapper.incrementClick(event.getTenantId(), event.getLinkId(), event.getCode(), date);
            // A8: dimension buckets share the dedup transaction, so one eventId can
            // never double count a bucket; parsing never throws (see ClickDimensions)
            for (ClickDimensions.Dimension dim : ClickDimensions.of(event.getUserAgent(), event.getReferer())) {
                clickDimDailyMapper.incrementClick(event.getTenantId(), event.getLinkId(),
                        date, dim.type(), dim.value());
            }
            // C3 (D-C3-11): the drawn A/B bucket is its own dimension; no variant, no row.
            if (event.getVariant() != null) {
                clickDimDailyMapper.incrementClick(event.getTenantId(), event.getLinkId(),
                        date, ClickDimensions.TYPE_VARIANT, event.getVariant());
            }
            // B-wave2 B5: a click that won the dedup insert leaves one PENDING webhook
            // delivery in the SAME transaction — replayed events early-return above,
            // so one eventId can never enqueue twice (D-Bw2-9). A non-duplicate failure
            // propagates up and rolls the whole consume transaction into the DLQ (S1).
            webhookService.recordClickDelivery(event);
            return true;
        }));
    }

    /** Per-dimension_value click counts for the window, descending; owner check happens at the controller. */
    public List<DimBreakdown> breakdown(long linkId, String dimType, int days) {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(days - 1L);
        return clickDimDailyMapper.selectBreakdown(linkId, dimType, from, today);
    }

    /**
     * One definition throughout (T-M1): every number in this response covers the
     * same last-{@code days} window and is read from the same source, the
     * lf_click_log detail rows. A detail row exists from the sync write while
     * the D7 double write is on, or from the consumer's backfill — written in
     * the same transaction as the lf_click_daily increment — so a day's clicks
     * and UV always describe the same set of clicks. Previously totalClicks was
     * the all-time aggregate total while totalUv was windowed, and the daily
     * clicks column came from the async aggregate while UV came from the sync
     * detail, so an MQ backlog could show a day with UV &gt; clicks.
     * totalClicks is exactly the sum of the daily clicks column; totalUv is the
     * window-wide distinct-IP count (not the sum of the daily UV column).
     * S/N wave (audit S4-1): the three reads also share one read-only RR
     * snapshot (see {@link #snapshotTx}), so "the same set of clicks" is a
     * transactional fact, not just a same-source/same-window one.
     */
    public LinkStatsResponse stats(Long linkId, String code, int days) {
        LocalDate today = LocalDate.now();
        LocalDate fromDate = today.minusDays(days - 1L);
        LocalDateTime from = fromDate.atStartOfDay();
        LocalDateTime toExclusive = today.plusDays(1).atStartOfDay();
        // S/N wave (audit S4-1): the three reads run inside ONE read-only RR
        // transaction, so they share a single consistent snapshot — a click
        // landing between two of the reads can no longer make one response
        // describe two different click sets (UV > clicks within a response).
        StatsReads reads = snapshotTx.execute(status -> new StatsReads(
                readTotalUv(linkId, from, toExclusive),
                readDailyClicks(linkId, from, toExclusive),
                readDailyUv(linkId, from, toExclusive)));
        List<DailyClicks> daily = new ArrayList<>();
        long total = 0;
        for (LocalDate d = fromDate; !d.isAfter(today); d = d.plusDays(1)) {
            long clicks = reads.clicksByDate().getOrDefault(d, 0L);
            total += clicks;
            daily.add(new DailyClicks(d, clicks, reads.uvByDate().getOrDefault(d, 0L)));
        }
        return new LinkStatsResponse(code, total, reads.totalUv(), daily);
    }

    /** The three snapshot reads of {@link #stats}, composed by the caller. */
    protected record StatsReads(long totalUv, Map<LocalDate, Long> clicksByDate,
                                Map<LocalDate, Long> uvByDate) {
    }

    // The three reads are separate protected methods on purpose: the
    // StatsWindowConsistencyIT seam overrides one of them to land a click
    // between two reads on a second connection (the PR-A insertSuccessor
    // precedent). Production behavior is exactly the mapper calls below.

    protected long readTotalUv(Long linkId, LocalDateTime from, LocalDateTime toExclusive) {
        return clickLogMapper.countDistinctIps(linkId, from, toExclusive);
    }

    protected Map<LocalDate, Long> readDailyClicks(Long linkId, LocalDateTime from, LocalDateTime toExclusive) {
        return clickLogMapper.selectDailyClicks(linkId, from, toExclusive).stream()
                .collect(Collectors.toMap(DailyClickCount::getStatDate, DailyClickCount::getClicks));
    }

    protected Map<LocalDate, Long> readDailyUv(Long linkId, LocalDateTime from, LocalDateTime toExclusive) {
        return clickLogMapper.selectDailyUv(linkId, from, toExclusive).stream()
                .collect(Collectors.toMap(DailyUv::getStatDate, DailyUv::getUv));
    }

    /**
     * Batch 3b: A/B distribution-fit diagnostic (see {@link AbReport} for the
     * honest framing). Observed counts come from the variant dimension buckets;
     * weights come from the CURRENT variant configuration. Buckets whose
     * variant was deleted since are reported as orphaned rows outside the fit.
     * Owner check happens at the controller.
     *
     * <p>S/N wave (audit S1): before any fit, the applicability gate
     * ({@link AbReport#checkApplicability}) decides whether a global-weight
     * fit means anything at all for this window — conditional routing,
     * a configuration changed inside the window, or a configuration whose
     * stability cannot be proven all answer NOT_APPLICABLE with a stable
     * reason instead of a misleading SKEWED/CONSISTENT. Fewer than two
     * configured variants skip the gate entirely: no fit exists there
     * regardless of history, and {@code compute} reports the structural
     * TOO_FEW_VARIANTS reason itself.
     */
    public AbReportResponse abReport(Long linkId, String code, int days) {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(days - 1L);
        Map<String, Long> observedByKey = clickDimDailyMapper
                .selectBreakdown(linkId, ClickDimensions.TYPE_VARIANT, from, today)
                .stream().collect(Collectors.toMap(DimBreakdown::getDimValue, DimBreakdown::getClicks));
        List<LinkVariant> configured = linkVariantMapper.selectByLinkId(linkId);
        List<AbReport.VariantInput> variants = new ArrayList<>();
        for (LinkVariant variant : configured) {
            variants.add(new AbReport.VariantInput(variant.getVariantKey(), variant.getWeight(),
                    observedByKey.getOrDefault(variant.getVariantKey(), 0L)));
            observedByKey.remove(variant.getVariantKey());
        }
        List<AbReport.VariantInput> orphans = observedByKey.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new AbReport.VariantInput(e.getKey(), 0, e.getValue()))
                .toList();
        AbReport.Result result;
        if (configured.size() < 2) {
            result = AbReport.compute(variants, orphans);
        } else {
            boolean anyConditional = configured.stream().anyMatch(variant -> {
                MatchRule rule = MatchRule.parseLenient(variant.getMatchRule());
                return rule != null && rule.isConstraining();
            });
            LocalDateTime configInstalledAt = configured.stream()
                    .map(LinkVariant::getCreatedAt).filter(Objects::nonNull)
                    .max(LocalDateTime::compareTo).orElse(null);
            ShortLink link = shortLinkMapper.selectById(linkId);
            LocalDateTime linkCreatedAt = link == null ? null : link.getCreatedAt();
            AbReport.NotApplicableReason gate = AbReport.checkApplicability(anyConditional,
                    !orphans.isEmpty(), configInstalledAt, linkCreatedAt, from.atStartOfDay());
            result = gate != null
                    ? AbReport.notApplicable(variants, orphans, gate)
                    : AbReport.compute(variants, orphans);
        }
        List<AbReportResponse.VariantRow> rows = result.rows().stream()
                .map(r -> new AbReportResponse.VariantRow(r.key(), r.weight(), r.observed(),
                        r.expected(), r.share(), r.ciLow(), r.ciHigh(), r.orphaned()))
                .toList();
        return new AbReportResponse(code, days, result.totalClicks(), result.chiSquare(),
                result.degreesOfFreedom(), result.pValue(), result.verdict().name(),
                result.reason() == null ? null : result.reason().name(), rows);
    }

    /** One raw-click export: CSV body plus whether the row cap cut it short. */
    public record ClicksExport(String csv, boolean truncated, long exportedRows) {
    }

    private static final int EXPORT_BATCH = 1000;

    /**
     * Batch 3b: raw click rows of the window as CSV, stitched from id-cursor
     * batches of at most {@value #EXPORT_BATCH} (never one unbounded select).
     * The cap is an operator property; hitting it appends a truncation comment
     * line and flags the result so the controller can set X-Export-Truncated.
     * Columns are exactly what lf_click_log stores — no derived dimensions.
     */
    public ClicksExport exportClicks(Long linkId, int days) {
        LocalDate today = LocalDate.now();
        LocalDateTime from = today.minusDays(days - 1L).atStartOfDay();
        LocalDateTime toExclusive = today.plusDays(1).atStartOfDay();
        int maxRows = Math.max(1, properties.getClicksExportMaxRows());
        StringBuilder sb = new StringBuilder("\uFEFFclicked_at,ip,user_agent,referer,variant\n");
        long lastId = 0;
        long exported = 0;
        while (exported < maxRows) {
            int batchSize = (int) Math.min(EXPORT_BATCH, maxRows - exported);
            List<ClickLog> batch = clickLogMapper.selectExportBatch(linkId, from, toExclusive, lastId, batchSize);
            if (batch.isEmpty()) {
                break;
            }
            for (ClickLog row : batch) {
                sb.append(clickCsvRow(row));
                exported++;
                lastId = row.getId();
            }
            if (batch.size() < batchSize) {
                break;
            }
        }
        boolean truncated = false;
        if (exported >= maxRows) {
            // A full final batch proves nothing by itself; probe one more row.
            truncated = !clickLogMapper.selectExportBatch(linkId, from, toExclusive, lastId, 1).isEmpty();
            if (truncated) {
                sb.append("# truncated at ").append(maxRows).append(" rows\n");
            }
        }
        return new ClicksExport(sb.toString(), truncated, exported);
    }

    private static String clickCsvRow(ClickLog row) {
        return String.join(",",
                CsvFormat.escape(row.getClickedAt() == null ? "" : row.getClickedAt().toString()),
                CsvFormat.escapeSpreadsheet(row.getIp()),
                CsvFormat.escapeSpreadsheet(row.getUserAgent()),
                CsvFormat.escapeSpreadsheet(row.getReferer()),
                CsvFormat.escapeSpreadsheet(row.getVariant())) + "\n";
    }

    public PageResponse<ClickDetail> clicks(Long linkId, int page, int size) {
        Page<ClickLog> result = clickLogMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<ClickLog>()
                        .eq(ClickLog::getLinkId, linkId)
                        .orderByDesc(ClickLog::getClickedAt));
        List<ClickDetail> records = result.getRecords().stream()
                .map(r -> new ClickDetail(r.getClickedAt(), r.getIp(), r.getUserAgent(), r.getReferer()))
                .toList();
        return new PageResponse<>(records, result.getTotal(), result.getCurrent(), result.getSize(), result.getPages());
    }
}
