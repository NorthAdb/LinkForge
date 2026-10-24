package com.linkforge.stats.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One dim_value bucket of a breakdown query, clicks descending. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DimBreakdown {
    private String dimValue;
    private long clicks;
}
