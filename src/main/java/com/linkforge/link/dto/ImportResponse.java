package com.linkforge.link.dto;

import java.util.List;

/**
 * D-C3-18: per-record outcome of a bulk CSV import. {@code row} is the 1-based FILE
 * line the record starts on (header = 1, first data row = 2); {@code reason} is the
 * same short string a plain 400 would carry and never echoes cell content.
 */
public record ImportResponse(int successCount, int failureCount,
                             List<RowSuccess> successes, List<RowFailure> failures) {

    public record RowSuccess(int row, String code) {
    }

    public record RowFailure(int row, String reason) {
    }
}
