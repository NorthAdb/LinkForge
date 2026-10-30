package com.linkforge.link.controller;

import com.linkforge.link.service.RedirectOutcome;
import com.linkforge.link.service.ShortLinkService;
import com.linkforge.link.support.GatePages;
import com.linkforge.link.support.LinkAccessTokens;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@RestController
@RequiredArgsConstructor
public class RedirectController {

    private static final MediaType HTML_UTF8 = new MediaType("text", "html", StandardCharsets.UTF_8);

    private final ShortLinkService shortLinkService;
    private final LinkAccessTokens linkAccessTokens;

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

    /** B3 unlock: anonymous form post by design; rate limiting and BCrypt live in the
     * service, so filter-level exceptions never bypass the two-tier limit. */
    @PostMapping("/r/{code}/unlock")
    public ResponseEntity<String> unlock(
            @PathVariable String code,
            @RequestParam(value = "password", required = false) String password,
            HttpServletRequest request) {
        ShortLinkService.UnlockResult result = shortLinkService.unlock(code, password, request);
        if (result.unlocked()) {
            ResponseCookie cookie = ResponseCookie.from(linkAccessTokens.cookieName(code), result.cookieValue())
                    .path("/r/" + code)
                    .httpOnly(true)
                    .sameSite("Lax")
                    .maxAge(Duration.ofSeconds(linkAccessTokens.ttlSeconds()))
                    .secure(request.isSecure())
                    .build();
            return ResponseEntity.status(HttpStatus.SEE_OTHER)
                    .header(HttpHeaders.SET_COOKIE, cookie.toString())
                    .location(URI.create("/r/" + code))
                    .build();
        }
        if (result.protectedLink()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .contentType(HTML_UTF8)
                    .header("Cache-Control", "no-store")
                    .header("Content-Security-Policy", GatePages.CSP)
                    .header("X-Content-Type-Options", "nosniff")
                    .body(GatePages.unlock(code, "密码错误 / Wrong password"));
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create("/r/" + code))
                .build();
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
            return withBody ? html(builder).body(GatePages.block()) : builder.build();
        }
        if (outcome instanceof RedirectOutcome.Locked) {
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(withBody
                            ? HttpStatus.OK : HttpStatus.UNAUTHORIZED)
                    .header("Cache-Control", "no-store")
                    .header("Content-Security-Policy", GatePages.CSP)
                    .header("X-Content-Type-Options", "nosniff");
            return withBody ? html(builder).body(GatePages.unlock(code, null)) : builder.build();
        }
        if (outcome instanceof RedirectOutcome.Warn warn) {
            ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.OK)
                    .header("Cache-Control", "no-store")
                    .header("Referrer-Policy", "no-referrer")
                    .header("Content-Security-Policy", GatePages.CSP)
                    .header("X-Content-Type-Options", "nosniff");
            return withBody
                    ? html(builder).body(GatePages.warn(warn.url(), warn.asciiHost(), warn.code()))
                    : builder.build();
        }
        throw new IllegalStateException("unknown redirect outcome " + outcome.getClass());
    }

    /** HTML bodies must not fall through to StringHttpMessageConverter's text/plain default. */
    private static ResponseEntity.BodyBuilder html(ResponseEntity.BodyBuilder builder) {
        return builder.contentType(HTML_UTF8);
    }
}
