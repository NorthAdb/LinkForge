package com.linkforge.link.support;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TargetPolicyTest {

    private static final TargetPolicy.Config EMPTY = TargetPolicy.Config.EMPTY;
    private static final TargetPolicy.Config BLOCKED_EVIL = new TargetPolicy.Config(
            Set.of(TargetPolicy.normalizeEntry("evil.test")), Set.of());
    private static final TargetPolicy.Config BOTH_LISTS = new TargetPolicy.Config(
            Set.of(TargetPolicy.normalizeEntry("dual.test")),
            Set.of(TargetPolicy.normalizeEntry("dual.test"), TargetPolicy.normalizeEntry("warn.test")));

    private static TargetPolicy.Action action(String url, TargetPolicy.Config config, boolean forCreate) {
        return TargetPolicy.evaluate(url, config, forCreate).action();
    }

    @Test
    void suffixMatchStaysOnLabelBoundaries() {
        assertEquals(TargetPolicy.Action.BLOCK, action("https://evil.test/x", BLOCKED_EVIL, true));
        assertEquals(TargetPolicy.Action.BLOCK, action("https://a.b.evil.test/x", BLOCKED_EVIL, true));
        assertEquals(TargetPolicy.Action.BLOCK, action("https://EVIL.TEST./x", BLOCKED_EVIL, true),
                "trailing dot and case fold away");
        assertEquals(TargetPolicy.Action.ALLOW, action("https://notevil.test/x", BLOCKED_EVIL, true));
        assertEquals(TargetPolicy.Action.ALLOW, action("https://evil.test.cn/x", BLOCKED_EVIL, true));
        assertEquals(TargetPolicy.Action.ALLOW, action("https://evil-test.com/x", BLOCKED_EVIL, true));
    }

    @Test
    void idnListingsMatchPunycodeUrls() {
        TargetPolicy.Config unicodeList = new TargetPolicy.Config(
                Set.of(TargetPolicy.normalizeEntry("bücher.test")), Set.of());
        assertEquals("xn--bcher-kva.test", TargetPolicy.normalizeEntry("bücher.test"));
        assertEquals(TargetPolicy.Action.BLOCK, action("https://xn--bcher-kva.test/x", unicodeList, true));
        // the reverse spelling normalizes to the same entry
        TargetPolicy.Config punycodeList = new TargetPolicy.Config(
                Set.of(TargetPolicy.normalizeEntry("xn--bcher-kva.test")), Set.of());
        assertEquals(TargetPolicy.Action.BLOCK, action("https://xn--bcher-kva.test/y", punycodeList, true));
    }

    @Test
    void blockWinsOverWarn() {
        assertEquals(TargetPolicy.Action.BLOCK,
                TargetPolicy.evaluate("https://dual.test/x", BOTH_LISTS, true).action());
        assertEquals(TargetPolicy.Action.WARN,
                TargetPolicy.evaluate("https://sub.warn.test/x", BOTH_LISTS, true).action());
    }

    @Test
    void emptyListsAllowEverything() {
        assertEquals(TargetPolicy.Action.ALLOW, action("https://evil.example.com/x", EMPTY, true),
                "a domain named evil is still just a name");
        assertEquals(TargetPolicy.Action.ALLOW, action("https://example.com/", EMPTY, false));
    }

    @Test
    void userinfoTargetsAreRejectedAtCreateWarnedAtRedirect() {
        TargetPolicy.Verdict create = TargetPolicy.evaluate(
                "https://paypal.com@evil.test/steal", EMPTY, true);
        assertEquals(TargetPolicy.Action.BLOCK, create.action());
        assertEquals(TargetPolicy.Reason.USERINFO, create.reason());

        TargetPolicy.Verdict redirect = TargetPolicy.evaluate(
                "https://user:pass@example.com/steal", EMPTY, false);
        assertEquals(TargetPolicy.Action.WARN, redirect.action());
        assertEquals(TargetPolicy.Reason.USERINFO, redirect.reason());

        // an @ inside the path is not userinfo
        assertEquals(TargetPolicy.Action.ALLOW,
                action("https://example.com/path@x", EMPTY, true));
    }

    @Test
    void unparseableUrlsFallOpen() {
        assertEquals(TargetPolicy.Action.ALLOW,
                action("ht tp://not a url", EMPTY, true));
        assertEquals(TargetPolicy.Action.ALLOW,
                action("https:///no-host-at-all", EMPTY, true));
        assertNull(TargetPolicy.asciiHost("https:///no-host-at-all"));
    }

    @Test
    void asciiHostExtractsTheNormalizedHost() {
        assertEquals("example.com", TargetPolicy.asciiHost("https://Example.com/deep/path?q=1"));
        assertEquals("xn--bcher-kva.test", TargetPolicy.asciiHost("https://xn--bcher-kva.test/x"));
    }
}
