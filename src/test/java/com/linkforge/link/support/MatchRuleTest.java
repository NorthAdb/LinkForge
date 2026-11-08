package com.linkforge.link.support;

import com.linkforge.common.exception.BizException;
import com.linkforge.link.entity.LinkVariant;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MatchRuleTest {

    /** 2026-01-05 is a Monday; 2026-01-03 is a Saturday. */
    private static final Instant MONDAY_10Z = Instant.parse("2026-01-05T10:00:00Z");
    private static final Instant SATURDAY_10Z = Instant.parse("2026-01-03T10:00:00Z");

    private static LinkVariant variant(String key, int weight, String matchRule) {
        LinkVariant variant = new LinkVariant();
        variant.setVariantKey(key);
        variant.setWeight(weight);
        variant.setMatchRule(matchRule);
        return variant;
    }

    private static void assertBadRequest(String json) {
        BizException ex = assertThrows(BizException.class, () -> MatchRule.parse(json),
                "expected 400 for: " + json);
        assertEquals(400, ex.getHttpStatus(), "for: " + json);
    }

    @Test
    void blankRuleIsWildcard() {
        assertNull(MatchRule.parse(null));
        assertNull(MatchRule.parse("  "));
        assertNull(MatchRule.validate(null));
        assertEquals("{\"devices\":[\"mobile\"]}", MatchRule.validate("  {\"devices\":[\"mobile\"]}  "));
    }

    @Test
    void parseRejectsMalformedAndUnknownShapes() {
        assertBadRequest("{oops");
        assertBadRequest("[\"mobile\"]");
        assertBadRequest("{\"geo\":[\"cn\"]}");
        assertBadRequest("{\"devices\":\"mobile\"}");
        assertBadRequest("{\"devices\":[42]}");
    }

    @Test
    void parseRejectsOutOfDomainValues() {
        assertBadRequest("{\"devices\":[\"hoverboard\"]}");
        assertBadRequest("{\"os\":[\"dos\"]}");
        // The ClickDimensions vocabularies are accepted verbatim.
        MatchRule.parse("{\"devices\":[\"desktop\",\"mobile\",\"tablet\",\"bot\",\"unknown\"],"
                + "\"os\":[\"ios\",\"android\",\"windows\",\"macos\",\"linux\",\"other\",\"unknown\"]}");
    }

    @Test
    void parseRejectsBadLanguages() {
        assertBadRequest("{\"languages\":[\"123\"]}");
        assertBadRequest("{\"languages\":[\"e\"]}");
        // Regional entries normalize to their primary tag instead of failing.
        MatchRule rule = MatchRule.parse("{\"languages\":[\"zh-CN\"]}");
        assertTrue(rule.matches("desktop", "windows", "zh", MONDAY_10Z));
        assertFalse(rule.matches("desktop", "windows", "en", MONDAY_10Z));
    }

    @Test
    void parseRejectsBadTimeWindows() {
        assertBadRequest("{\"timeWindows\":[{\"from\":\"9:00\",\"to\":\"18:00\",\"tz\":\"UTC\"}]}");
        assertBadRequest("{\"timeWindows\":[{\"from\":\"24:00\",\"to\":\"18:00\",\"tz\":\"UTC\"}]}");
        assertBadRequest("{\"timeWindows\":[{\"from\":\"09:00\",\"to\":\"09:00\",\"tz\":\"UTC\"}]}");
        assertBadRequest("{\"timeWindows\":[{\"from\":\"09:00\",\"to\":\"18:00\",\"tz\":\"Mars/Olympus\"}]}");
        assertBadRequest("{\"timeWindows\":[{\"from\":\"09:00\",\"to\":\"18:00\"}]}");
        assertBadRequest("{\"timeWindows\":[{\"from\":\"09:00\",\"to\":\"18:00\",\"tz\":\"UTC\",\"weekdays\":[0]}]}");
        assertBadRequest("{\"timeWindows\":[{\"from\":\"09:00\",\"to\":\"18:00\",\"tz\":\"UTC\",\"weekdays\":[8]}]}");
        assertBadRequest("{\"timeWindows\":[{\"from\":\"09:00\",\"to\":\"18:00\",\"tz\":\"UTC\",\"zone\":\"UTC\"}]}");
    }

    @Test
    void deviceAndOsDimensionsAreAnded() {
        MatchRule rule = MatchRule.parse("{\"devices\":[\"mobile\",\"tablet\"],\"os\":[\"ios\"]}");
        assertTrue(rule.matches("mobile", "ios", null, MONDAY_10Z));
        assertTrue(rule.matches("tablet", "ios", null, MONDAY_10Z));
        assertFalse(rule.matches("mobile", "android", null, MONDAY_10Z));
        assertFalse(rule.matches("desktop", "ios", null, MONDAY_10Z));
        assertFalse(rule.matches(null, "ios", null, MONDAY_10Z));
    }

    @Test
    void languageDimensionUsesThePrimaryTag() {
        assertEquals("zh", MatchRule.primaryTag("zh-CN,zh;q=0.9,en;q=0.8"));
        assertEquals("en", MatchRule.primaryTag("en-US"));
        assertNull(MatchRule.primaryTag(null));
        assertNull(MatchRule.primaryTag("*"));
        MatchRule rule = MatchRule.parse("{\"languages\":[\"zh\"]}");
        assertTrue(rule.matches("desktop", "windows",
                MatchRule.primaryTag("zh-Hans-CN"), MONDAY_10Z));
        assertFalse(rule.matches("desktop", "windows",
                MatchRule.primaryTag("ja-JP"), MONDAY_10Z));
    }

    @Test
    void timeWindowMatchesInsideAndOutside() {
        MatchRule rule = MatchRule.parse(
                "{\"timeWindows\":[{\"from\":\"09:00\",\"to\":\"17:00\",\"tz\":\"UTC\"}]}");
        assertTrue(rule.matches("desktop", "windows", null, MONDAY_10Z));
        assertFalse(rule.matches("desktop", "windows", null, Instant.parse("2026-01-05T20:00:00Z")));
        // The zone is the rule's, not the caller's: 10Z is 18:00 in Shanghai -> outside 09-17 CST.
        MatchRule shanghai = MatchRule.parse(
                "{\"timeWindows\":[{\"from\":\"09:00\",\"to\":\"17:00\",\"tz\":\"Asia/Shanghai\"}]}");
        assertFalse(shanghai.matches("desktop", "windows", null, MONDAY_10Z));
        assertTrue(shanghai.matches("desktop", "windows", null, Instant.parse("2026-01-05T02:00:00Z")));
    }

    @Test
    void overnightWindowWrapsMidnight() {
        MatchRule rule = MatchRule.parse(
                "{\"timeWindows\":[{\"from\":\"22:00\",\"to\":\"02:00\",\"tz\":\"UTC\"}]}");
        assertTrue(rule.matches("desktop", "windows", null, Instant.parse("2026-01-05T23:30:00Z")));
        assertTrue(rule.matches("desktop", "windows", null, Instant.parse("2026-01-06T01:00:00Z")));
        assertFalse(rule.matches("desktop", "windows", null, MONDAY_10Z));
    }

    @Test
    void weekdayFilterApplies() {
        MatchRule rule = MatchRule.parse(
                "{\"timeWindows\":[{\"from\":\"00:00\",\"to\":\"23:59\",\"tz\":\"UTC\",\"weekdays\":[6,7]}]}");
        assertTrue(rule.matches("desktop", "windows", null, SATURDAY_10Z));
        assertFalse(rule.matches("desktop", "windows", null, MONDAY_10Z));
    }

    @Test
    void filterCandidatesNarrowsAndKeepsWildcards() {
        LinkVariant mobile = variant("M", 1, "{\"devices\":[\"mobile\"]}");
        LinkVariant desktop = variant("D", 1, "{\"devices\":[\"desktop\"]}");
        LinkVariant wild = variant("W", 1, null);
        List<LinkVariant> all = List.of(mobile, desktop, wild);

        assertEquals(List.of(mobile, wild),
                MatchRule.filterCandidates(all, "mobile", "ios", null, MONDAY_10Z));
        assertEquals(List.of(desktop, wild),
                MatchRule.filterCandidates(all, "desktop", "windows", null, MONDAY_10Z));
        assertEquals(List.of(wild),
                MatchRule.filterCandidates(all, "bot", "other", null, MONDAY_10Z));
    }

    @Test
    void filterCandidatesFallsBackToTheFullSetWhenNothingMatches() {
        LinkVariant mobile = variant("M", 1, "{\"devices\":[\"mobile\"]}");
        LinkVariant desktop = variant("D", 1, "{\"devices\":[\"desktop\"]}");
        List<LinkVariant> all = List.of(mobile, desktop);

        List<LinkVariant> candidates =
                MatchRule.filterCandidates(all, "bot", "other", null, MONDAY_10Z);
        assertEquals(all, candidates, "no match must fall back, never empty the draw");
        // The fallback set still draws by weight: fixed sources pin both ends.
        assertSame(mobile, VariantPicker.pick(candidates, () -> 0.0));
        assertSame(desktop, VariantPicker.pick(candidates, () -> 0.99));
    }

    @Test
    void filterCandidatesTreatsCorruptStoredRuleAsWildcard() {
        LinkVariant corrupt = variant("C", 1, "{not json");
        LinkVariant desktop = variant("D", 1, "{\"devices\":[\"desktop\"]}");
        assertEquals(List.of(corrupt),
                MatchRule.filterCandidates(List.of(corrupt, desktop), "mobile", "ios", null, MONDAY_10Z));
        assertNull(MatchRule.parseLenient("{not json"));
    }
}
