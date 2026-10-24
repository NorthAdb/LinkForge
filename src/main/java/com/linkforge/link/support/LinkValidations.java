package com.linkforge.link.support;

import com.linkforge.common.exception.BizException;

import java.net.URI;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure validation helpers shared by create/update and the redirect path, unit-tested
 * without Spring.
 */
public final class LinkValidations {

    private static final Pattern CUSTOM_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{4,16}$");
    /** All >=4 chars: shorter reserved words never pass the length rule anyway. */
    private static final Set<String> RESERVED_CODES = Set.of(
            "actuator", "health", "auth", "login", "register", "links",
            "export", "stats", "swagger", "docs", "static", "index");

    private LinkValidations() {
    }

    public static void requireHttpUrl(String url) {
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new BizException("originalUrl must start with http:// or https://");
        }
        if (hasControlChar(url)) {
            throw new BizException("originalUrl must not contain control characters");
        }
        try {
            URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new BizException("originalUrl is not a valid URI");
        }
    }

    public static void requireValidCustomCode(String code) {
        if (!CUSTOM_CODE_PATTERN.matcher(code).matches()) {
            throw new BizException("customCode must be 4-16 chars of letters, digits, underscore or hyphen");
        }
        if (RESERVED_CODES.contains(code.toLowerCase())) {
            throw new BizException("customCode is reserved");
        }
    }

    /** Guards against legacy rows with malformed targets: never 500, never count the click. */
    public static boolean isRenderableUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        if (hasControlChar(url)) {
            return false;
        }
        try {
            URI.create(url);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean hasControlChar(String url) {
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }
}
