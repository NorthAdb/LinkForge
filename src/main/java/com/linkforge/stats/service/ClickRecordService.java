package com.linkforge.stats.service;

import com.linkforge.config.LinkForgeProperties;
import com.linkforge.link.entity.ShortLink;
import com.linkforge.stats.entity.ClickLog;
import com.linkforge.stats.mapper.ClickLogMapper;
import com.linkforge.stats.mq.ClickEvent;
import com.linkforge.stats.mq.ClickEventPublisher;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClickRecordService {

    private final ClickLogMapper clickLogMapper;
    private final ClickEventPublisher clickEventPublisher;
    private final LinkForgeProperties properties;

    public void recordClick(ShortLink link, HttpServletRequest request) {
        LocalDateTime now = LocalDateTime.now();
        String ip = resolveClientIp(request);
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
        clickLogMapper.insert(logRow);

        if (properties.getMq().isAsyncClickEnabled()) {
            try {
                clickEventPublisher.publish(new ClickEvent(
                        link.getTenantId(), link.getId(), link.getCode(), ip, ua, referer, now));
            } catch (Exception ex) {
                log.warn("async click publish failed code={}: {}", link.getCode(), ex.getMessage());
            }
        }
    }

    private static String resolveClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
