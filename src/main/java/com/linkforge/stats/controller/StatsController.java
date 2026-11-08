package com.linkforge.stats.controller;

import com.linkforge.common.exception.BizException;
import com.linkforge.common.result.ApiResponse;
import com.linkforge.common.result.PageResponse;
import com.linkforge.link.dto.ShortLinkDetail;
import com.linkforge.link.service.ShortLinkService;
import com.linkforge.stats.dto.AbReportResponse;
import com.linkforge.stats.dto.ClickDetail;
import com.linkforge.stats.dto.DimBreakdown;
import com.linkforge.stats.dto.LinkStatsResponse;
import com.linkforge.stats.service.ClickStatsService;
import com.linkforge.stats.support.ClickDimensions;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequiredArgsConstructor
public class StatsController {

    private static final Set<String> DIM_TYPES = Set.of(
            ClickDimensions.TYPE_DEVICE, ClickDimensions.TYPE_REFERER_HOST,
            ClickDimensions.TYPE_OS, ClickDimensions.TYPE_BROWSER, ClickDimensions.TYPE_VARIANT);

    private final ShortLinkService shortLinkService;
    private final ClickStatsService clickStatsService;

    @GetMapping("/api/links/{code}/stats")
    public ApiResponse<LinkStatsResponse> stats(@PathVariable String code,
                                                @RequestParam(defaultValue = "7") int days) {
        ShortLinkDetail link = shortLinkService.detail(code);
        int window = days < 1 ? 7 : Math.min(days, 90);
        return ApiResponse.ok(clickStatsService.stats(link.getId(), code, window));
    }

    public static final String DIM_REQUIRED =
            "dim is required and must be one of device, referer_host, os, browser, variant";

    /** A8: per-bucket click counts (device / referer_host / os / browser). Dimension clicks are not UV. */
    @GetMapping("/api/links/{code}/stats/breakdown")
    public ApiResponse<List<DimBreakdown>> breakdown(@PathVariable String code,
                                                     @RequestParam(required = false) String dim,
                                                     @RequestParam(defaultValue = "7") int days) {
        if (dim == null || dim.isBlank() || !DIM_TYPES.contains(dim)) {
            throw new BizException(DIM_REQUIRED);
        }
        ShortLinkDetail link = shortLinkService.detail(code);
        int window = days < 1 ? 7 : Math.min(days, 90);
        return ApiResponse.ok(clickStatsService.breakdown(link.getId(), dim, window));
    }

    @GetMapping("/api/links/{code}/clicks")
    public ApiResponse<PageResponse<ClickDetail>> clicks(@PathVariable String code,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "20") int size) {
        ShortLinkDetail link = shortLinkService.detail(code);
        int p = Math.max(1, page);
        int s = size < 1 || size > 100 ? 20 : size;
        return ApiResponse.ok(clickStatsService.clicks(link.getId(), p, s));
    }

    /** Batch 3b: distribution-fit diagnostic of the variant buckets against the
     * configured weights (see AbReport) — not a conversion test. */
    @GetMapping("/api/links/{code}/stats/ab-report")
    public ApiResponse<AbReportResponse> abReport(@PathVariable String code,
                                                  @RequestParam(defaultValue = "7") int days) {
        ShortLinkDetail link = shortLinkService.detail(code);
        int window = days < 1 ? 7 : Math.min(days, 90);
        return ApiResponse.ok(clickStatsService.abReport(link.getId(), code, window));
    }

    /** Batch 3b: raw click rows of the window as CSV. Same ownership path as the
     * clicks detail endpoint; the truncation flag rides a response header. */
    @GetMapping(value = "/api/links/{code}/clicks/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<String> exportClicks(@PathVariable String code,
                                               @RequestParam(defaultValue = "30") int days) {
        ShortLinkDetail link = shortLinkService.detail(code);
        int window = days < 1 ? 30 : Math.min(days, 90);
        ClickStatsService.ClicksExport export = clickStatsService.exportClicks(link.getId(), window);
        ResponseEntity.BodyBuilder body = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=clicks-" + code + ".csv");
        if (export.truncated()) {
            body.header("X-Export-Truncated", "true");
        }
        return body.body(export.csv());
    }
}
