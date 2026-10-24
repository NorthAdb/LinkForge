package com.linkforge.stats.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClickDimensionsTest {

    @Test
    void deviceBucketsByUserAgent() {
        assertEquals("mobile", ClickDimensions.device("Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X)"));
        assertEquals("mobile", ClickDimensions.device("Mozilla/5.0 (Linux; Android 13; Pixel 7) Mobile Safari"));
        assertEquals("tablet", ClickDimensions.device("Mozilla/5.0 (iPad; CPU OS 16_0)"));
        assertEquals("tablet", ClickDimensions.device("Mozilla/5.0 (Linux; Android 12; Galaxy Tab) Chrome"));
        assertEquals("desktop", ClickDimensions.device("Mozilla/5.0 (Windows NT 10.0; Win64; x64)"));
        assertEquals("desktop", ClickDimensions.device("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15)"));
        assertEquals("desktop", ClickDimensions.device("Mozilla/5.0 (X11; Linux x86_64)"));
    }

    @Test
    void botBeatsMobileHeuristics() {
        assertEquals("bot", ClickDimensions.device("Mozilla/5.0 (Linux; Android 6) Googlebot-Mobile"));
        assertEquals("bot", ClickDimensions.device("curl/8.4.0"));
        assertEquals("bot", ClickDimensions.device("python-requests/2.31.0"));
        assertEquals("bot", ClickDimensions.device("Java/17.0.9"));
        assertEquals("bot", ClickDimensions.device("Mozilla/5.0 HeadlessChrome/120"));
    }

    @Test
    void missingOrGarbageUserAgentFallsBackToUnknown() {
        assertEquals("unknown", ClickDimensions.device(null));
        assertEquals("unknown", ClickDimensions.device("   "));
        assertEquals("unknown", ClickDimensions.device("\u0000\u0001weird"));
        assertEquals("unknown", ClickDimensions.device("some-agent-nobody-knows"));
    }

    @Test
    void refererNormalizesHostCaseAndWww() {
        assertEquals("example.com", ClickDimensions.refererHost("https://Www.Example.COM/path?q=1"));
        assertEquals("example.com", ClickDimensions.refererHost("http://example.com"));
        assertEquals("sub.example.com", ClickDimensions.refererHost("https://sub.example.com"));
    }

    @Test
    void refererMissingOrBrokenIsDirect() {
        assertEquals("direct", ClickDimensions.refererHost(null));
        assertEquals("direct", ClickDimensions.refererHost(""));
        assertEquals("direct", ClickDimensions.refererHost("not a url \r\n at all"));
        assertEquals("direct", ClickDimensions.refererHost("javascript:void(0)"));
    }

    @Test
    void overlongHostIsTruncated() {
        String host = "a".repeat(200) + ".example.com";
        String bucket = ClickDimensions.refererHost("https://" + host + "/");
        assertEquals(128, bucket.length());
    }

    @Test
    void ofProducesBothDimensions() {
        var dims = ClickDimensions.of("curl/8.4.0", "https://www.example.com/x");
        assertEquals(2, dims.size());
        assertEquals("device", dims.get(0).type());
        assertEquals("bot", dims.get(0).value());
        assertEquals("referer_host", dims.get(1).type());
        assertEquals("example.com", dims.get(1).value());
    }
}
