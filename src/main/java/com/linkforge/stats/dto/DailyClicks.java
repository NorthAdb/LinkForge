package com.linkforge.stats.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDate;

@Data
@AllArgsConstructor
public class DailyClicks {
    private LocalDate date;
    private long clicks;
}
