package com.linkforge.stats.support;

import java.net.URI;
import java.util.List;

/**
 * Coarse deterministic dimension buckets for click analytics (deepen A8). Pure
 * string rules on purpose: no parsing library, no network, never throws — the
 * consumer must not die on a malformed User-Agent or Referer. Device order
 * matters: bot beats mobile-tablet heuristics (crawlers often claim mobile).
 */
public final class ClickDimensions {

    public static final String TYPE_DEVICE = "device";
    public static final String TYPE_REFERER_HOST = "referer_host";
    public static final String TYPE_OS = "os";
    public static final String TYPE_BROWSER = "browser";
    /** C3: A/B split bucket; not UA-derived, written only when the click drew a variant. */
    public static final String TYPE_VARIANT = "variant";

    private static final List<String> BOT_TOKENS = List.of(
            "bot", "spider", "crawler", "slurp", "curl", "wget", "python-requests",
            "okhttp", "java/", "go-http-client", "headlesschrome",
            "facebookexternalhit", "bingpreview", "lighthouse");
    private static final int MAX_VALUE_LENGTH = 128;

    private ClickDimensions() {
    }

    /** The dimension rows one consumed click contributes. */
    public static List<Dimension> of(String userAgent, String referer) {
        return List.of(
                new Dimension(TYPE_DEVICE, device(userAgent)),
                new Dimension(TYPE_REFERER_HOST, refererHost(referer)),
                new Dimension(TYPE_OS, os(userAgent)),
                new Dimension(TYPE_BROWSER, browser(userAgent)));
    }

    public static String device(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "unknown";
        }
        String ua = userAgent.toLowerCase();
        if (BOT_TOKENS.stream().anyMatch(ua::contains)) {
            return "bot";
        }
        if (ua.contains("ipad") || (ua.contains("android") && !ua.contains("mobile"))) {
            return "tablet";
        }
        if (ua.contains("iphone") || ua.contains("ipod") || ua.contains("mobile")) {
            return "mobile";
        }
        if (ua.contains("windows") || ua.contains("macintosh") || ua.contains("x11") || ua.contains("linux")) {
            return "desktop";
        }
        return "unknown";
    }

    /**
     * No bot bucket: bots still resolve an os from their claimed UA. Android UAs
     * claim Linux and iPhone UAs claim Mac OS X, so those checks must come first.
     */
    public static String os(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "unknown";
        }
        String ua = userAgent.toLowerCase();
        if (ua.contains("iphone") || ua.contains("ipod") || ua.contains("ipad")) {
            return "ios";
        }
        if (ua.contains("android")) {
            return "android";
        }
        if (ua.contains("windows")) {
            return "windows";
        }
        if (ua.contains("mac os x") || ua.contains("macintosh")) {
            return "macos";
        }
        if (ua.contains("linux") || ua.contains("x11")) {
            return "linux";
        }
        return "other";
    }

    /** Order is the contract: WeChat and Edge embed Chrome/, and Safari/ trails every Chromium UA. */
    public static String browser(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return "unknown";
        }
        String ua = userAgent.toLowerCase();
        if (BOT_TOKENS.stream().anyMatch(ua::contains)) {
            return "bot";
        }
        if (ua.contains("micromessenger")) {
            return "wechat";
        }
        if (ua.contains("edg/")) {
            return "edge";
        }
        if (ua.contains("chrome/") || ua.contains("crios")) {
            return "chrome";
        }
        if (ua.contains("firefox/") || ua.contains("fxios")) {
            return "firefox";
        }
        if (ua.contains("safari/")) {
            return "safari";
        }
        return "other";
    }

    /** Normalized referer host: lowercase, "www." stripped, missing/broken -> "direct". */
    public static String refererHost(String referer) {
        if (referer == null || referer.isBlank()) {
            return "direct";
        }
        String host;
        try {
            host = URI.create(referer.trim()).getHost();
        } catch (IllegalArgumentException ex) {
            return "direct";
        }
        if (host == null || host.isBlank()) {
            return "direct";
        }
        host = host.toLowerCase();
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        return host.length() > MAX_VALUE_LENGTH ? host.substring(0, MAX_VALUE_LENGTH) : host;
    }

    public record Dimension(String type, String value) {
    }
}
