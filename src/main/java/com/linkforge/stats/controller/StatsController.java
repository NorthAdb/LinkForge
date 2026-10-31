package com.linkforge.stats.controller;

import com.linkforge.common.exception.BizException;
import com.linkforge.common.result.ApiResponse;
import com.linkforge.common.result.PageResponse;
import com.linkforge.link.dto.ShortLinkDetail;
import com.linkforge.link.service.ShortLinkService;
import com.linkforge.stats.dto.ClickDetail;
import com.linkforge.stats.dto.DimBreakdown;
import com.linkforge.stats.dto.LinkStatsResponse;
import com.linkforge.stats.service.ClickStatsService;
import com.linkforge.stats.support.ClickDimensions;
import lombok.RequiredArgsConstructor;
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
            ClickDimensions.TYPE_OS, ClickDimensions.TYPE_BROWSER);

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
            "dim is required and must be one of device, referer_host, os, browser";

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
}
