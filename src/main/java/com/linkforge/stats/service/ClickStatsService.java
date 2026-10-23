package com.linkforge.stats.service;

import com.linkforge.stats.dto.DailyClicks;
import com.linkforge.stats.dto.LinkStatsResponse;
import com.linkforge.stats.entity.ClickDaily;
import com.linkforge.stats.entity.ConsumedEvent;
import com.linkforge.stats.mapper.ClickDailyMapper;
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
    private final TransactionTemplate dedupTx;

    public ClickStatsService(ConsumedEventMapper consumedEventMapper,
                             ClickDailyMapper clickDailyMapper,
                             PlatformTransactionManager transactionManager) {
        this.consumedEventMapper = consumedEventMapper;
        this.clickDailyMapper = clickDailyMapper;
        this.dedupTx = new TransactionTemplate(transactionManager);
    }

    /**
     * Idempotent consume. Dedup insert and daily increment share one short
     * transaction, so a crash between them can neither double count nor silently
     * drop a click. A duplicate key inside the callback is a plain skip: MySQL
     * rolls back only the failed statement.
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
            clickDailyMapper.incrementClick(event.getTenantId(), event.getLinkId(), event.getCode(), date);
            return true;
        }));
    }

    public LinkStatsResponse stats(Long linkId, String code, int days) {
        long total = clickDailyMapper.sumClicks(linkId);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(days - 1L);
        Map<LocalDate, Long> byDate = clickDailyMapper.selectRange(linkId, from, today).stream()
                .collect(Collectors.toMap(ClickDaily::getStatDate, ClickDaily::getClicks));
        List<DailyClicks> daily = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
            daily.add(new DailyClicks(d, byDate.getOrDefault(d, 0L)));
        }
        return new LinkStatsResponse(code, total, daily);
    }
}
