package com.linkforge.stats.controller;

import com.linkforge.common.result.ApiResponse;
import com.linkforge.link.dto.ShortLinkDetail;
import com.linkforge.link.service.ShortLinkService;
import com.linkforge.stats.dto.LinkStatsResponse;
import com.linkforge.stats.service.ClickStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class StatsController {

    private final ShortLinkService shortLinkService;
    private final ClickStatsService clickStatsService;

    @GetMapping("/api/links/{code}/stats")
    public ApiResponse<LinkStatsResponse> stats(@PathVariable String code,
                                                @RequestParam(defaultValue = "7") int days) {
        ShortLinkDetail link = shortLinkService.detail(code);
        int window = days < 1 ? 7 : Math.min(days, 90);
        return ApiResponse.ok(clickStatsService.stats(link.getId(), code, window));
    }
}
