package com.linkforge.stats.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
public class LinkStatsResponse {
    private String code;
    private long totalClicks;
    private long totalUv;
    private List<DailyClicks> daily;
}
