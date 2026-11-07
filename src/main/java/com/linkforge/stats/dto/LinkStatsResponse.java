package com.linkforge.stats.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Windowed link stats (T-M1): every figure covers the same last-N-days window
 * as {@code daily} and is read from the click detail rows — see
 * {@code ClickStatsService.stats}. The console already labelled totalClicks
 * as a window figure ("近 N 天") while the backend used to return the
 * all-time total; the field names stay, the definitions now match the labels.
 */
@Data
@AllArgsConstructor
public class LinkStatsResponse {
    private String code;
    /** Clicks inside the window; exactly the sum of the daily clicks column. */
    private long totalClicks;
    /** Distinct IPs inside the whole window (not the sum of the daily UV column). */
    private long totalUv;
    private List<DailyClicks> daily;
}
