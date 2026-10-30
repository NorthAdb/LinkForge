package com.linkforge.link.controller;

import com.linkforge.common.result.ApiResponse;
import com.linkforge.common.result.PageResponse;
import com.linkforge.link.dto.CreateShortLinkRequest;
import com.linkforge.link.dto.CreateShortLinkResponse;
import com.linkforge.link.dto.QrShareRequest;
import com.linkforge.link.dto.QrShareResponse;
import com.linkforge.link.dto.ShortLinkDetail;
import com.linkforge.link.dto.UpdateShortLinkRequest;
import com.linkforge.link.service.QrMaterialService;
import com.linkforge.link.service.ShortLinkService;
import com.linkforge.link.support.QrParams;
import com.linkforge.security.QrSharePrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/links")
@RequiredArgsConstructor
public class ShortLinkController {

    private final ShortLinkService shortLinkService;
    private final QrMaterialService qrMaterialService;

    @PostMapping
    public ApiResponse<CreateShortLinkResponse> create(@Valid @RequestBody CreateShortLinkRequest request,
                                                       @RequestHeader(value = "Idempotency-Key", required = false)
                                                       String idempotencyKey) {
        return ApiResponse.ok(shortLinkService.create(request, idempotencyKey));
    }

    @GetMapping
    public ApiResponse<PageResponse<ShortLinkDetail>> list(@RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(shortLinkService.list(page, size));
    }

    @GetMapping(value = "/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<String> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=links.csv")
                .body(shortLinkService.exportOwnCsv());
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

    @PostMapping("/{code}/enable")
    public ApiResponse<Void> enable(@PathVariable String code) {
        shortLinkService.enable(code);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{code}")
    public ApiResponse<Void> delete(@PathVariable String code) {
        shortLinkService.delete(code);
        return ApiResponse.ok();
    }

    /** C1 wave B (D-C1-2..5): PNG material whose matrix carries only /r/{code}. */
    @GetMapping("/{code}/qr.png")
    public ResponseEntity<byte[]> qrPng(@PathVariable String code,
                                        @RequestParam(required = false) String size,
                                        @RequestParam(required = false) String margin,
                                        @RequestParam(required = false) String ec) {
        QrParams params = QrParams.parse(size, margin, ec);
        boolean shared = openedByShare(code);
        qrMaterialService.resolveForMaterial(code, shared);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CACHE_CONTROL, shared ? "private, no-store" : "private, max-age=60")
                .body(qrMaterialService.render(code, params));
    }

    /** C1 wave B (D-C1-2): mints the short-lived signed material URL; ownership first
     * so a share on someone else's code is a 404, never a leak. */
    @PostMapping("/{code}/qr-share")
    public ApiResponse<QrShareResponse> qrShare(@PathVariable String code,
                                                @RequestBody(required = false) QrShareRequest request) {
        qrMaterialService.resolveForMaterial(code, false);
        return ApiResponse.ok(new QrShareResponse(qrMaterialService.shareUrl(code, request)));
    }

    private static boolean openedByShare(String code) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof QrSharePrincipal principal
                && principal.code().equals(code);
    }
}
