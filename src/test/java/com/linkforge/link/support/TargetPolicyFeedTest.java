package com.linkforge.link.support;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Batch 3b: the feed set BLOCKs with its own reason, under the local lists. */
class TargetPolicyFeedTest {

    private static final TargetPolicy.Config FEED_ONLY = new TargetPolicy.Config(
            Set.of(), Set.of(), Set.of(TargetPolicy.normalizeEntry("feed.example")));

    @Test
    void feedHostBlocksWithFeedReason() {
        TargetPolicy.Verdict verdict = TargetPolicy.evaluate("https://feed.example/x", FEED_ONLY, true);
        assertEquals(TargetPolicy.Action.BLOCK, verdict.action());
        assertEquals(TargetPolicy.Reason.MALICIOUS_FEED, verdict.reason());
        assertEquals("malicious_feed", verdict.reason().tag());
        // suffix matching is the same label-boundary rule as the local lists
        assertEquals(TargetPolicy.Action.BLOCK,
                TargetPolicy.evaluate("https://a.feed.example/x", FEED_ONLY, true).action());
        assertEquals(TargetPolicy.Action.ALLOW,
                TargetPolicy.evaluate("https://notfeed.example/x", FEED_ONLY, true).action());
    }

    @Test
    void localBlockKeepsAttributionOverFeed() {
        TargetPolicy.Config both = new TargetPolicy.Config(
                Set.of(TargetPolicy.normalizeEntry("dual.example")),
                Set.of(),
                Set.of(TargetPolicy.normalizeEntry("dual.example")));
        TargetPolicy.Verdict verdict = TargetPolicy.evaluate("https://dual.example/", both, true);
        assertEquals(TargetPolicy.Action.BLOCK, verdict.action());
        assertEquals(TargetPolicy.Reason.BLOCK_LIST, verdict.reason());
    }

    @Test
    void feedBlockBeatsWarn() {
        TargetPolicy.Config feedAndWarn = new TargetPolicy.Config(
                Set.of(),
                Set.of(TargetPolicy.normalizeEntry("dual.example")),
                Set.of(TargetPolicy.normalizeEntry("dual.example")));
        TargetPolicy.Verdict verdict = TargetPolicy.evaluate("https://dual.example/", feedAndWarn, true);
        assertEquals(TargetPolicy.Action.BLOCK, verdict.action());
        assertEquals(TargetPolicy.Reason.MALICIOUS_FEED, verdict.reason());
    }

    @Test
    void legacyTwoListConfigHasNoFeed() {
        TargetPolicy.Config legacy = new TargetPolicy.Config(Set.of(), Set.of());
        assertEquals(TargetPolicy.Action.ALLOW,
                TargetPolicy.evaluate("https://feed.example/", legacy, true).action());
    }
}
