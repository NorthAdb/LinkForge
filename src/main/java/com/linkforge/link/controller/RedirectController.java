package com.linkforge.link.controller;

import com.linkforge.link.service.ShortLinkService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequiredArgsConstructor
public class RedirectController {

    private final ShortLinkService shortLinkService;

    @GetMapping("/r/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
        String target = shortLinkService.resolveAndRecord(code, request);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(target)).build();
    }
}
