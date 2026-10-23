package com.linkforge.stats.dto;

import lombok.Data;

import java.time.LocalDate;

/** Result row of the per-day distinct-IP query. */
@Data
public class DailyUv {
    private LocalDate statDate;
    private Long uv;
}
