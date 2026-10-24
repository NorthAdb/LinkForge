package com.linkforge.link.support;

import com.linkforge.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinkValidationsTest {

    @Test
    void acceptsHttpAndHttpsUrls() {
        assertDoesNotThrow(() -> LinkValidations.requireHttpUrl("http://a.com/x?y=1"));
        assertDoesNotThrow(() -> LinkValidations.requireHttpUrl("https://example.com/path?q=1#frag"));
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThrows(BizException.class, () -> LinkValidations.requireHttpUrl("ftp://example.com/x"));
        assertThrows(BizException.class, () -> LinkValidations.requireHttpUrl("javascript:alert(1)"));
    }

    @Test
    void rejectsControlCharsAndMalformedUris() {
        assertThrows(BizException.class, () -> LinkValidations.requireHttpUrl("https://a.com/\r\nX: 1"));
        assertThrows(BizException.class, () -> LinkValidations.requireHttpUrl("https://a.com/\u0000"));
        assertThrows(BizException.class, () -> LinkValidations.requireHttpUrl("https://a.com/a b"));
    }

    @Test
    void renderableCheckMatchesValidationButNeverThrows() {
        assertTrue(LinkValidations.isRenderableUrl("https://example.com/x"));
        assertFalse(LinkValidations.isRenderableUrl("https://example.com/\r\nInjected: yes"));
        assertFalse(LinkValidations.isRenderableUrl("https://a.com/a b"));
        assertFalse(LinkValidations.isRenderableUrl(null));
        assertFalse(LinkValidations.isRenderableUrl("  "));
    }

    @Test
    void customCodeLengthAndCharsetEnforced() {
        assertDoesNotThrow(() -> LinkValidations.requireValidCustomCode("abcd"));
        assertDoesNotThrow(() -> LinkValidations.requireValidCustomCode("aB_9".repeat(4)));
        assertThrows(BizException.class, () -> LinkValidations.requireValidCustomCode("abc"));
        assertThrows(BizException.class, () -> LinkValidations.requireValidCustomCode("a".repeat(17)));
        assertThrows(BizException.class, () -> LinkValidations.requireValidCustomCode("ab!c"));
    }

    @Test
    void reservedWordsRejectedCaseInsensitively() {
        assertThrows(BizException.class, () -> LinkValidations.requireValidCustomCode("stats"));
        assertThrows(BizException.class, () -> LinkValidations.requireValidCustomCode("DOCS"));
        assertThrows(BizException.class, () -> LinkValidations.requireValidCustomCode("admin"));
        assertThrows(BizException.class, () -> LinkValidations.requireValidCustomCode("ADMIN"));
        assertThrows(BizException.class, () -> LinkValidations.requireValidCustomCode("favicon"));
        assertDoesNotThrow(() -> LinkValidations.requireValidCustomCode("docs2"));
    }
}
