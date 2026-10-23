package com.linkforge.stats.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
public class ClickDetail {
    private LocalDateTime clickedAt;
    private String ip;
    private String userAgent;
    private String referer;
}
