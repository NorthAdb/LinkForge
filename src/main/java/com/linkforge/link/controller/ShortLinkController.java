package com.linkforge.link.controller;

import com.linkforge.common.result.ApiResponse;
import com.linkforge.common.result.PageResponse;
import com.linkforge.link.dto.CreateShortLinkRequest;
import com.linkforge.link.dto.CreateShortLinkResponse;
import com.linkforge.link.dto.ShortLinkDetail;
import com.linkforge.link.dto.UpdateShortLinkRequest;
import com.linkforge.link.service.ShortLinkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/links")
@RequiredArgsConstructor
public class ShortLinkController {

    private final ShortLinkService shortLinkService;

    @PostMapping
    public ApiResponse<CreateShortLinkResponse> create(@Valid @RequestBody CreateShortLinkRequest request) {
        return ApiResponse.ok(shortLinkService.create(request));
    }

    @GetMapping
    public ApiResponse<PageResponse<ShortLinkDetail>> list(@RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(shortLinkService.list(page, size));
    }

    @GetMapping("/{code}")
    public ApiResponse<ShortLinkDetail> detail(@PathVariable String code) {
        return ApiResponse.ok(shortLinkService.detail(code));
    }

    @PatchMapping("/{code}")
    public ApiResponse<ShortLinkDetail> update(@PathVariable String code,
                                               @Valid @RequestBody UpdateShortLinkRequest request) {
        return ApiResponse.ok(shortLinkService.update(code, request));
    }

    @PostMapping("/{code}/disable")
    public ApiResponse<Void> disable(@PathVariable String code) {
        shortLinkService.disable(code);
        return ApiResponse.ok();
    }
}
