package com.linkforge.link.support;

import com.linkforge.config.LinkForgeProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClickFilterTest {

    private static ClickFilter filter(boolean enabled, boolean prefetchHeaders, List<String> tokens) {
        LinkForgeProperties props = new LinkForgeProperties();
        LinkForgeProperties.ClickFilter cfg = new LinkForgeProperties.ClickFilter();
        cfg.setEnabled(enabled);
        cfg.setPrefetchHeadersEnabled(prefetchHeaders);
        cfg.setBotUaTokens(tokens);
        props.setClickFilter(cfg);
        return new ClickFilter(props);
    }

    private static ClickFilter defaultFilter() {
        return filter(true, true, new LinkForgeProperties.ClickFilter().getBotUaTokens());
    }

    private static MockHttpServletRequest request(String ua, String header, String value) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        if (ua != null) {
            req.addHeader("User-Agent", ua);
        }
        if (header != null) {
            req.addHeader(header, value);
        }
        return req;
    }

    private static void assertSkipped(ClickFilter filter, String reason, MockHttpServletRequest req) {
        Optional<ClickFilter.SkipReason> skip = filter.classify(req);
        assertEquals(Optional.of(ClickFilter.SkipReason.valueOf(reason)), skip, "" + req);
    }

    private static void assertCounted(ClickFilter filter, MockHttpServletRequest req) {
        assertEquals(Optional.empty(), filter.classify(req), "" + req);
    }

    @Test
    void prefetchHeaderSpellingsSkip() {
        ClickFilter f = defaultFilter();
        assertSkipped(f, "PREFETCH", request(null, "Sec-Purpose", "prefetch"));
        assertSkipped(f, "PREFETCH", request(null, "Sec-Purpose", "prefetch;prerender"));
        assertSkipped(f, "PREFETCH", request(null, "Sec-Purpose", "Prefetch; Anonymous-Client-IP"));
        assertSkipped(f, "PREFETCH", request(null, "Purpose", "prefetch"));
        assertSkipped(f, "PREFETCH", request(null, "Purpose", "preview"));
        assertSkipped(f, "PREFETCH", request(null, "X-Purpose", "preview"));
        assertSkipped(f, "PREFETCH", request(null, "X-Moz", "prefetch"));
    }

    @Test
    void namedPreviewBotsSkip() {
        ClickFilter f = defaultFilter();
        assertSkipped(f, "PREVIEW_BOT", request("Slackbot-LinkExpanding 1.0 (+https://api.slack.com/robots)", null, null));
        assertSkipped(f, "PREVIEW_BOT", request("facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)", null, null));
        assertSkipped(f, "PREVIEW_BOT", request("Twitterbot/1.0", null, null));
        assertSkipped(f, "PREVIEW_BOT", request("TelegramBot (like TwitterBot)", null, null));
        assertSkipped(f, "PREVIEW_BOT", request("WhatsApp/2.23.20.0", null, null));
        assertSkipped(f, "PREVIEW_BOT", request("Mozilla/5.0 (compatible; Discordbot/2.0; +https://discordapp.com)", null, null));
        assertSkipped(f, "PREVIEW_BOT", request("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)", null, null));
        assertSkipped(f, "PREVIEW_BOT", request("Mozilla/5.0 (compatible; Baiduspider/2.0; +http://www.baidu.com/search/spider.html)", null, null));
    }

    @Test
    void realClientsAndToolsStillCount() {
        ClickFilter f = defaultFilter();
        assertCounted(f, request("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/126.0.0.0 Safari/537.36", null, null));
        assertCounted(f, request("curl/8.4.0", null, null));
        assertCounted(f, request("Java/17.0.10", null, null));
        assertCounted(f, request("python-requests/2.31.0", null, null));
        assertCounted(f, request("[FBAN/FBIOS;FBAV/420.0.0.0]", null, null));
        assertCounted(f, request("Instagram 300.0.0.0 (iPhone)", null, null));
        assertCounted(f, request("Mozilla/5.0 MicroMessenger/8.0.49", null, null));
        assertCounted(f, request("Twitter for iPhone/9.53", null, null));
        assertCounted(f, request(null, null, null));
        assertCounted(f, request("", null, null));
        // exact token match only — a header that merely contains the letters counts
        assertCounted(f, request(null, "Sec-Purpose", "prefetchx"));
    }

    @Test
    void disabledFilterCountsEverything() {
        ClickFilter f = filter(false, true, new LinkForgeProperties.ClickFilter().getBotUaTokens());
        assertCounted(f, request(null, "Sec-Purpose", "prefetch"));
        assertCounted(f, request("Slackbot-LinkExpanding 1.0", null, null));
    }

    @Test
    void prefetchHeaderGateAloneCanBeDisabled() {
        ClickFilter f = filter(true, false, new LinkForgeProperties.ClickFilter().getBotUaTokens());
        assertCounted(f, request(null, "Sec-Purpose", "prefetch"));
        assertSkipped(f, "PREVIEW_BOT", request("Slackbot-LinkExpanding 1.0", null, null));
    }

    @Test
    void customTokenListReplacesTheDefaults() {
        ClickFilter f = filter(true, true, List.of("mybot"));
        assertSkipped(f, "PREVIEW_BOT", request("MyBot/1.0 (custom)", null, null));
        assertCounted(f, request("Slackbot-LinkExpanding 1.0", null, null));
    }
}
