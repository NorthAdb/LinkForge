package com.linkforge.stats.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.common.util.ClientIps;
import com.linkforge.config.LinkForgeProperties;
import com.linkforge.link.entity.ShortLink;
import com.linkforge.stats.entity.ClickLog;
import com.linkforge.stats.entity.Outbox;
import com.linkforge.stats.mapper.ClickLogMapper;
import com.linkforge.stats.mapper.OutboxMapper;
import com.linkforge.stats.mq.ClickEvent;
import com.linkforge.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClickRecordService {

    private final ClickLogMapper clickLogMapper;
    private final OutboxMapper outboxMapper;
    private final ObjectMapper objectMapper;
    private final LinkForgeProperties properties;

    /**
     * A7: the only writers are this transaction. The sync lf_click_log row (when
     * enabled) and the PENDING outbox row commit atomically; the hot path never
     * touches RabbitMQ, delivery is ClickOutboxRelay's job. eventId stays
     * "log-{id}" while the sync write is on so replays dedupe, and becomes a
     * stable UUID persisted in the outbox when it is off.
     */
    @Transactional
    public void recordClick(ShortLink link, HttpServletRequest request, String variant) {
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
        logRow.setVariant(variant);
        if (properties.getMq().isSyncClickEnabled()) {
            clickLogMapper.insert(logRow);
        }

        if (properties.getMq().isAsyncClickEnabled()) {
            String eventId = logRow.getId() != null
                    ? "log-" + logRow.getId()
                    : UUID.randomUUID().toString();
            Long userId = UserContext.get() == null ? null : UserContext.get().getUserId();
            ClickEvent event = new ClickEvent(
                    eventId, link.getTenantId(), link.getId(), link.getCode(),
                    userId, ip, ua, referer, now, variant);
            Outbox outbox = new Outbox();
            outbox.setEventId(eventId);
            outbox.setPayload(toJson(event));
            outbox.setStatus(Outbox.STATUS_PENDING);
            outbox.setAttempts(0);
            outboxMapper.insert(outbox);
        }
    }

    private String toJson(ClickEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("click event serialization failed", ex);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
