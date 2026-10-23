package com.linkforge.stats.service;

import com.linkforge.common.util.ClientIps;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.link.entity.ShortLink;
import com.linkforge.stats.entity.ClickLog;
import com.linkforge.stats.mapper.ClickLogMapper;
import com.linkforge.stats.mq.ClickEvent;
import com.linkforge.stats.mq.ClickEventPublisher;
import com.linkforge.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClickRecordService {

    private final ClickLogMapper clickLogMapper;
    private final ClickEventPublisher clickEventPublisher;
    private final LinkForgeProperties properties;

    /**
     * D7=A double write: sync row in lf_click_log plus async publish. A failed publish
     * is logged and later backfilled by ClickLogReconciler, so the redirect path never
     * blocks on MQ.
     */
    public void recordClick(ShortLink link, HttpServletRequest request) {
        LocalDateTime now = LocalDateTime.now();
        String ip = ClientIps.resolve(request, properties.getRateLimit().isTrustXff());
        String ua = truncate(request.getHeader("User-Agent"), 512);
        String referer = truncate(request.getHeader("Referer"), 512);

        ClickLog logRow = new ClickLog();
        logRow.setTenantId(link.getTenantId());
        logRow.setLinkId(link.getId());
        logRow.setCode(link.getCode());
        logRow.setIp(ip);
        logRow.setUserAgent(ua);
        logRow.setReferer(referer);
        logRow.setClickedAt(now);
        if (properties.getMq().isSyncClickEnabled()) {
            clickLogMapper.insert(logRow);
        }

        if (properties.getMq().isAsyncClickEnabled()) {
            try {
                String eventId = logRow.getId() != null
                        ? "log-" + logRow.getId()
                        : UUID.randomUUID().toString();
                Long userId = UserContext.get() == null ? null : UserContext.get().getUserId();
                clickEventPublisher.publish(new ClickEvent(
                        eventId, link.getTenantId(), link.getId(), link.getCode(),
                        userId, ip, ua, referer, now));
            } catch (Exception ex) {
                log.warn("async click publish failed code={}: {}", link.getCode(), ex.getMessage());
            }
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
