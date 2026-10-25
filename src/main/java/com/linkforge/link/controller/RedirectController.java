package com.linkforge.link.controller;

import com.linkforge.link.service.RedirectOutcome;
import com.linkforge.link.service.ShortLinkService;
import com.linkforge.link.support.GatePages;
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
    public ResponseEntity<String> redirect(
            @Parameter(description = "short code, case-sensitive (exact match)")
            @PathVariable String code, HttpServletRequest request) {
        return render(shortLinkService.resolveForGet(code, request), code, true);
    }

    @RequestMapping(value = "/r/{code}", method = RequestMethod.HEAD)
    public ResponseEntity<String> probe(
            @Parameter(description = "short code, case-sensitive (exact match)")
            @PathVariable String code, HttpServletRequest request) {
        return render(shortLinkService.resolveForProbe(code, request), code, false);
    }

    /** D-Bw345-51 verdicts to responses; HEAD shares every status but never a body. */
    private ResponseEntity<String> render(RedirectOutcome outcome, String code, boolean withBody) {
        if (outcome instanceof RedirectOutcome.Redirect redirect) {
            return ResponseEntity.status(HttpStatus.FOUND)
                    .location(URI.create(redirect.url()))
                    .build();
        }
        if (outcome instanceof RedirectOutcome.Blocked) {
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .header("Cache-Control", "no-store")
                    .header("Content-Security-Policy", GatePages.CSP)
                    .header("X-Content-Type-Options", "nosniff");
            return withBody ? builder.body(GatePages.block()) : builder.build();
        }
        if (outcome instanceof RedirectOutcome.Warn warn) {
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.OK)
                    .header("Cache-Control", "no-store")
                    .header("Referrer-Policy", "no-referrer")
                    .header("Content-Security-Policy", GatePages.CSP)
                    .header("X-Content-Type-Options", "nosniff");
            return withBody
                    ? builder.body(GatePages.warn(warn.url(), warn.asciiHost(), warn.code()))
                    : builder.build();
        }
        throw new IllegalStateException("unknown redirect outcome " + outcome.getClass());
    }
}
