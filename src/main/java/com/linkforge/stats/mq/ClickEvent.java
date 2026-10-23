package com.linkforge.stats.mq;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClickEvent {
    private Long tenantId;
    private Long linkId;
    private String code;
    private String ip;
    private String userAgent;
    private String referer;
    private LocalDateTime clickedAt;
}
