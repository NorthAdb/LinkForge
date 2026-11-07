package com.linkforge.stats.dto;

import lombok.Data;

import java.time.LocalDate;

/** Result row of the per-day click-count query over the click detail rows. */
@Data
public class DailyClickCount {
    private LocalDate statDate;
    private Long clicks;
}
