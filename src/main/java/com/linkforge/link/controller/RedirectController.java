package com.linkforge.link.controller;

import com.linkforge.link.service.ShortLinkService;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequiredArgsConstructor
public class RedirectController {

    private final ShortLinkService shortLinkService;

    @GetMapping("/r/{code}")
    public ResponseEntity<Void> redirect(
            @Parameter(description = "short code, case-sensitive (exact match)")
            @PathVariable String code, HttpServletRequest request) {
        String target = shortLinkService.resolveAndRecord(code, request);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }

    @RequestMapping(value = "/r/{code}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> probe(
            @Parameter(description = "short code, case-sensitive (exact match)")
            @PathVariable String code, HttpServletRequest request) {
        String target = shortLinkService.resolveForProbe(code, request);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }
}
