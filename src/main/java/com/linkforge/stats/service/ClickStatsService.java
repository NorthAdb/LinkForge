package com.linkforge.stats.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linkforge.common.result.PageResponse;
import com.linkforge.stats.dto.ClickDetail;
import com.linkforge.stats.dto.DailyClicks;
import com.linkforge.stats.dto.DailyUv;
import com.linkforge.stats.dto.LinkStatsResponse;
import com.linkforge.stats.entity.ClickDaily;
import com.linkforge.stats.entity.ClickLog;
import com.linkforge.stats.entity.ConsumedEvent;
import com.linkforge.stats.mapper.ClickDailyMapper;
import com.linkforge.stats.mapper.ClickLogMapper;
import com.linkforge.stats.mapper.ConsumedEventMapper;
import com.linkforge.stats.mq.ClickEvent;
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
    private final ClickLogMapper clickLogMapper;
    private final TransactionTemplate dedupTx;

    public ClickStatsService(ConsumedEventMapper consumedEventMapper,
                             ClickDailyMapper clickDailyMapper,
                             ClickLogMapper clickLogMapper,
                             PlatformTransactionManager transactionManager) {
        this.consumedEventMapper = consumedEventMapper;
        this.clickDailyMapper = clickDailyMapper;
        this.clickLogMapper = clickLogMapper;
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
                // marker: this row is already counted under the UUID eventId, so the
                // reconciler (which replays implicit log-{id} rows) must skip it
                detail.setEventId(event.getEventId());
                clickLogMapper.insert(detail);
            }
            clickDailyMapper.incrementClick(event.getTenantId(), event.getLinkId(), event.getCode(), date);
            return true;
        }));
    }

    /**
     * Clicks come from lf_click_daily (async aggregate); UV is exact distinct-IP from
     * the sync lf_click_log detail, available while the D7 double write is on.
     */
    public LinkStatsResponse stats(Long linkId, String code, int days) {
        long total = clickDailyMapper.sumClicks(linkId);
        LocalDate today = LocalDate.now();
        LocalDate fromDate = today.minusDays(days - 1L);
        LocalDateTime from = fromDate.atStartOfDay();
        LocalDateTime toExclusive = today.plusDays(1).atStartOfDay();
        long totalUv = clickLogMapper.countDistinctIps(linkId, from, toExclusive);
        Map<LocalDate, Long> byDate = clickDailyMapper.selectRange(linkId, fromDate, today).stream()
                .collect(Collectors.toMap(ClickDaily::getStatDate, ClickDaily::getClicks));
        Map<LocalDate, Long> uvByDate = clickLogMapper.selectDailyUv(linkId, from, toExclusive).stream()
                .collect(Collectors.toMap(DailyUv::getStatDate, DailyUv::getUv));
        List<DailyClicks> daily = new ArrayList<>();
        for (LocalDate d = fromDate; !d.isAfter(today); d = d.plusDays(1)) {
            daily.add(new DailyClicks(d, byDate.getOrDefault(d, 0L), uvByDate.getOrDefault(d, 0L)));
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
