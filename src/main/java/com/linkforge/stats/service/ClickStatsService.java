package com.linkforge.stats.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linkforge.common.result.PageResponse;
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
import com.linkforge.stats.support.ClickDimensions;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ClickStatsService {

    private final ConsumedEventMapper consumedEventMapper;
    private final ClickDailyMapper clickDailyMapper;
    private final ClickDimDailyMapper clickDimDailyMapper;
    private final ClickLogMapper clickLogMapper;
    private final com.linkforge.webhook.service.WebhookService webhookService;
    private final TransactionTemplate dedupTx;

    public ClickStatsService(ConsumedEventMapper consumedEventMapper,
                             ClickDailyMapper clickDailyMapper,
                             ClickDimDailyMapper clickDimDailyMapper,
                             ClickLogMapper clickLogMapper,
                             com.linkforge.webhook.service.WebhookService webhookService,
                             PlatformTransactionManager transactionManager) {
        this.consumedEventMapper = consumedEventMapper;
        this.clickDailyMapper = clickDailyMapper;
        this.clickDimDailyMapper = clickDimDailyMapper;
        this.clickLogMapper = clickLogMapper;
        this.webhookService = webhookService;
        this.dedupTx = new TransactionTemplate(transactionManager);
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
     */
    public LinkStatsResponse stats(Long linkId, String code, int days) {
        LocalDate today = LocalDate.now();
        LocalDate fromDate = today.minusDays(days - 1L);
        LocalDateTime from = fromDate.atStartOfDay();
        LocalDateTime toExclusive = today.plusDays(1).atStartOfDay();
        long totalUv = clickLogMapper.countDistinctIps(linkId, from, toExclusive);
        Map<LocalDate, Long> byDate = clickLogMapper.selectDailyClicks(linkId, from, toExclusive).stream()
                .collect(Collectors.toMap(DailyClickCount::getStatDate, DailyClickCount::getClicks));
        Map<LocalDate, Long> uvByDate = clickLogMapper.selectDailyUv(linkId, from, toExclusive).stream()
                .collect(Collectors.toMap(DailyUv::getStatDate, DailyUv::getUv));
        List<DailyClicks> daily = new ArrayList<>();
        long total = 0;
        for (LocalDate d = fromDate; !d.isAfter(today); d = d.plusDays(1)) {
            long clicks = byDate.getOrDefault(d, 0L);
            total += clicks;
            daily.add(new DailyClicks(d, clicks, uvByDate.getOrDefault(d, 0L)));
        }
        return new LinkStatsResponse(code, total, totalUv, daily);
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
