package com.linkforge.link.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct proof that the gate pages escape every dynamic value. The ITs can only
 * prove this indirectly: URL validation rejects angle brackets in originalUrl
 * before a warn page ever renders, so the escaping itself is exercised here.
 */
class GatePagesTest {

    @Test
    void warnPageEscapesAngleBracketsQuotesAndAmpersand() {
        String page = GatePages.warn("<script>alert(\"x\")</script>&'", "evil.com", "ab12");
        assertTrue(page.contains("&lt;script&gt;"), page);
        assertTrue(page.contains("&amp;"), page);
        assertTrue(page.contains("&quot;"), page);
        assertTrue(page.contains("&#39;"), page);
        assertFalse(page.contains("<script"), page);
        assertFalse(page.contains("\"x\""), page);
    }

    @Test
    void warnPageEscapesHostAndContinueCode() {
        String page = GatePages.warn("https://ok.example", "<img>", "a\"b");
        assertTrue(page.contains("&lt;img&gt;"), page);
        // the code lands inside an href attribute, so a raw quote would break out
        assertTrue(page.contains("/r/a&quot;b?lf_continue=1"), page);
    }

    @Test
    void unlockPageEscapesCodeAndErrorMessage() {
        String page = GatePages.unlock("c<d", "bad <b>password</b> \"wrong\"");
        assertTrue(page.contains("/r/c&lt;d/unlock"), page);
        assertTrue(page.contains("&lt;b&gt;password&lt;/b&gt;"), page);
        assertTrue(page.contains("&quot;wrong&quot;"), page);
        assertFalse(page.contains("<b>"), page);
    }

    @Test
    void blockPageIsStaticAndNeverEchoesInput() {
        String page = GatePages.block();
        assertTrue(page.contains("已被拦截"), page);
        assertFalse(page.contains("%"), page);
    }
}
