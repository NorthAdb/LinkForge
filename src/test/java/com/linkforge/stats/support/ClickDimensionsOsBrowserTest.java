package com.linkforge.stats.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C2 wave D (D-C2-22..24): os/browser buckets from pure string rules, no UA
 * library, never throws. Priority contract: bot beats everything for browser;
 * WeChat beats Chrome; Edg/ beats Chrome; Safari only without a Chrome marker.
 */
class ClickDimensionsOsBrowserTest {

    private static String value(List<ClickDimensions.Dimension> rows, String type) {
        return rows.stream().filter(r -> r.type().equals(type)).findFirst()
                .orElseThrow(() -> new AssertionError("missing dimension row of type " + type))
                .value();
    }

    @Test
    void windowsEdgeIsEdgeNeverChrome() {
        String edge = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/120.0.0.0 Safari/537.36 Edg/120.0.2210.61";
        var rows = ClickDimensions.of(edge, null);
        assertEquals("windows", value(rows, "os"));
        assertEquals("edge", value(rows, "browser"));
    }

    @Test
    void wechatInnerBrowserBeatsChrome() {
        String wechat = "Mozilla/5.0 (Linux; Android 13; M2102J20SG) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/116.0.0.0 Mobile Safari/537.36 MicroMessenger/8.9.3";
        var rows = ClickDimensions.of(wechat, null);
        assertEquals("android", value(rows, "os"));
        assertEquals("wechat", value(rows, "browser"));
    }

    @Test
    void botMarkerWinsTheBrowserBucket() {
        var rows = ClickDimensions.of(
                "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)", null);
        assertEquals("bot", value(rows, "browser"));
        assertEquals("other", value(rows, "os"), "the bot's UA names no os we bucket");
    }

    @Test
    void chromeCriOSFirefoxAndSafariFollowThePriority() {
        var chrome = ClickDimensions.of("Mozilla/5.0 (Windows NT 10.0) AppleWebKit/537.36 (KHTML, like Gecko) "
                + "Chrome/119.0.0.0 Safari/537.36", null);
        assertEquals("chrome", value(chrome, "browser"));

        var crios = ClickDimensions.of("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/119.0.0.0 Mobile/15E148 Safari/604.1", null);
        assertEquals("ios", value(crios, "os"));
        assertEquals("chrome", value(crios, "browser"));

        var fxiOS = ClickDimensions.of("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) FxiOS/118.0 Mobile/15E148 Safari/605.1.15", null);
        assertEquals("ios", value(fxiOS, "os"));
        assertEquals("firefox", value(fxiOS, "browser"));

        var firefox = ClickDimensions.of("Mozilla/5.0 (X11; Linux x86_64; rv:118.0) Gecko/20100101 "
                + "Firefox/118.0", null);
        assertEquals("linux", value(firefox, "os"));
        assertEquals("firefox", value(firefox, "browser"));

        var safari = ClickDimensions.of("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15", null);
        assertEquals("macos", value(safari, "os"));
        assertEquals("safari", value(safari, "browser"), "Safari only when no Chrome marker preceded");
    }

    @Test
    void blankAndForeignUserAgentsFallToUnknownOrOtherWithoutThrowing() {
        var blank = ClickDimensions.of(null, null);
        assertEquals("unknown", value(blank, "os"));
        assertEquals("unknown", value(blank, "browser"));

        var foreign = ClickDimensions.of("MyOwnAgent/1.0", null);
        assertEquals("other", value(foreign, "os"));
        assertEquals("other", value(foreign, "browser"));

        assertTrue(ClickDimensions.of("   ", null).size() >= 4,
                "a blank UA still yields the full dimension row set");
    }

    @Test
    void osAndBrowserRideAlongTheExistingDimensions() {
        var rows = ClickDimensions.of("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36", "https://news.example.com/x");
        assertEquals(4, rows.size(), "device + referer_host + os + browser");
        assertTrue(rows.stream().anyMatch(r -> r.type().equals("device") && r.value().equals("desktop")));
        assertTrue(rows.stream().anyMatch(r -> r.type().equals("referer_host")
                && r.value().equals("news.example.com")));
    }
}
