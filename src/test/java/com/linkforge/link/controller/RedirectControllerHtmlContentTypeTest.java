package com.linkforge.link.controller;

import com.linkforge.link.entity.ShortLink;
import com.linkforge.link.service.RedirectOutcome;
import com.linkforge.link.service.ShortLinkService;
import com.linkforge.link.support.LinkAccessTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Gate HTML used to leave StringHttpMessageConverter's text/plain default, so
 * browsers showed the markup as source. Every response that carries an HTML body
 * must declare text/html;charset=UTF-8. HEAD stays body-less.
 */
@ExtendWith(MockitoExtension.class)
class RedirectControllerHtmlContentTypeTest {

    @Mock
    private ShortLinkService shortLinkService;
    @Mock
    private LinkAccessTokens linkAccessTokens;

    private RedirectController controller;

    @BeforeEach
    void setUp() {
        controller = new RedirectController(shortLinkService, linkAccessTokens);
    }

    @Test
    void lockedBlockedAndWarnGetsAreHtml() {
        when(shortLinkService.resolveForGet(eq("locked"), any()))
                .thenReturn(new RedirectOutcome.Locked(null));
        when(shortLinkService.resolveForGet(eq("blocked"), any()))
                .thenReturn(new RedirectOutcome.Blocked());
        when(shortLinkService.resolveForGet(eq("warn"), any()))
                .thenReturn(new RedirectOutcome.Warn("warn", "https://warn.example/x", "warn.example"));

        assertHtml(controller.redirect("locked", new MockHttpServletRequest()));
        ResponseEntity<String> blocked = controller.redirect("blocked", new MockHttpServletRequest());
        assertEquals(403, blocked.getStatusCode().value());
        assertHtml(blocked);
        assertHtml(controller.redirect("warn", new MockHttpServletRequest()));
    }

    @Test
    void wrongPasswordUnlockPageIsHtml() {
        ShortLink link = new ShortLink();
        link.setAccessPasswordHash("hash");
        when(shortLinkService.unlock(eq("locked"), eq("nope"), any()))
                .thenReturn(new ShortLinkService.UnlockResult(link, null));

        ResponseEntity<String> resp = controller.unlock("locked", "nope", new MockHttpServletRequest());
        assertEquals(401, resp.getStatusCode().value());
        assertHtml(resp);
    }

    @Test
    void headAndRedirectDoNotPretendToBeHtmlBodies() {
        when(shortLinkService.resolveForProbe(eq("locked"), any()))
                .thenReturn(new RedirectOutcome.Locked(null));
        when(shortLinkService.resolveForGet(eq("open"), any()))
                .thenReturn(new RedirectOutcome.Redirect(null, "https://example.com/ok", null));

        ResponseEntity<String> head = controller.probe("locked", new MockHttpServletRequest());
        assertEquals(401, head.getStatusCode().value());
        assertNull(head.getBody());

        ResponseEntity<String> jump = controller.redirect("open", new MockHttpServletRequest());
        assertEquals(302, jump.getStatusCode().value());
        assertNull(jump.getBody());
        MediaType ct = jump.getHeaders().getContentType();
        assertTrue(ct == null || !"html".equals(ct.getSubtype()));
    }

    private static void assertHtml(ResponseEntity<String> resp) {
        assertNotNull(resp.getBody());
        assertTrue(resp.getBody().contains("<html"), resp.getBody());
        MediaType ct = resp.getHeaders().getContentType();
        assertNotNull(ct, "HTML body must declare Content-Type");
        assertEquals("text", ct.getType());
        assertEquals("html", ct.getSubtype());
        assertEquals(StandardCharsets.UTF_8, ct.getCharset());
    }
}
