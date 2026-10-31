package com.linkforge.stats.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Click event contract. eventId is deterministic ("log-{clickLogId}") while the sync
 * click_log double-write is on, so the live consumer and the reconciliation replay of
 * the same click dedupe to a single count.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClickEvent {
    private String eventId;
    private Long tenantId;
    private Long linkId;
    private String code;
    private Long userId;
    private String ip;
    private String userAgent;
    private String referer;
    private LocalDateTime clickedAt;
    /** C3 (D-C3-11): the drawn A/B variant key; null when the link has no variants. */
    private String variant;
}
