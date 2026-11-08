package com.linkforge.stats.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Batch 3b: A/B distribution-fit diagnostic for one link over the window.
 * {@code totalClicks} counts only clicks drawn by CURRENTLY configured
 * variants (the fit population); buckets of since-deleted variants ride along
 * as rows with {@code orphaned=true} and null weight/expected. The verdict is
 * about the draw pipeline's fidelity to the configured weights — with clicks
 * as the only observable there is no conversion claim to make.
 */
@Data
@AllArgsConstructor
public class AbReportResponse {
    private String code;
    private int days;
    private long totalClicks;
    /** Null when no fit was computed (NOT_APPLICABLE / INSUFFICIENT_DATA). */
    private Double chiSquare;
    private int degreesOfFreedom;
    /** Null when no fit was computed. */
    private Double pValue;
    /** CONSISTENT / SKEWED / INSUFFICIENT_DATA / NOT_APPLICABLE. */
    private String verdict;
    private List<VariantRow> variants;

    @Data
    @AllArgsConstructor
    public static class VariantRow {
        private String key;
        /** Null on orphaned rows (variant deleted since the clicks). */
        private Integer weight;
        private long observed;
        /** Null on orphaned rows. */
        private Double expected;
        /** Share of the fit population; null on orphaned rows / empty window. */
        private Double share;
        private Double ciLow;
        private Double ciHigh;
        private boolean orphaned;
    }
}
