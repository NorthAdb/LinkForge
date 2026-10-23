package com.linkforge.link.controller;

import com.linkforge.common.result.ApiResponse;
import com.linkforge.link.dto.CreateShortLinkRequest;
import com.linkforge.link.dto.CreateShortLinkResponse;
import com.linkforge.link.service.ShortLinkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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
}
