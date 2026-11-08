package com.linkforge.link.support;

import com.linkforge.common.exception.BizException;
import com.linkforge.link.entity.LinkVariant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

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

    /** S/N wave (audit S4-4): an overnight window's weekday list names the
     * day the window OPENED on; the post-midnight tail is judged by the
     * previous local day. 2026-01-04 is a Sunday, -05 a Monday, -06 a
     * Tuesday, -07 a Wednesday. America/New_York springs forward on
     * 2026-03-08 (02:00 EST -> 03:00 EDT) and falls back on 2026-11-01
     * (02:00 EDT -> 01:00 EST, the 01:xx hour happens twice). */
    private static Stream<Arguments> overnightCases() {
        String monday = "{\"timeWindows\":[{\"from\":\"23:00\",\"to\":\"02:00\",\"tz\":\"UTC\",\"weekdays\":[1]}]}";
        String sunday = "{\"timeWindows\":[{\"from\":\"23:00\",\"to\":\"02:00\",\"tz\":\"UTC\",\"weekdays\":[7]}]}";
        String nySaturday = "{\"timeWindows\":[{\"from\":\"23:00\",\"to\":\"02:00\",\"tz\":\"America/New_York\",\"weekdays\":[6]}]}";
        return Stream.of(
                // Monday opening: head and tail both match
                Arguments.of(monday, "2026-01-05T23:30:00Z", true),
                Arguments.of(monday, "2026-01-06T01:00:00Z", true),
                // Monday 01:00 is the tail of SUNDAY's opening — not listed
                Arguments.of(monday, "2026-01-05T01:00:00Z", false),
                // Tuesday opening / Wednesday tail are not Monday's window
                Arguments.of(monday, "2026-01-06T23:30:00Z", false),
                Arguments.of(monday, "2026-01-07T01:00:00Z", false),
                // week boundary: Sunday opens, Monday's tail belongs to it
                Arguments.of(sunday, "2026-01-04T23:30:00Z", true),
                Arguments.of(sunday, "2026-01-05T01:00:00Z", true),
                Arguments.of(sunday, "2026-01-03T23:30:00Z", false),
                Arguments.of(sunday, "2026-01-04T01:00:00Z", false),
                // DST spring forward: Sat 2026-03-07 23:30 EST opens; the
                // 00:30 EST tail (05:30Z) is still Saturday's window
                Arguments.of(nySaturday, "2026-03-08T04:30:00Z", true),
                Arguments.of(nySaturday, "2026-03-08T05:30:00Z", true),
                // Sunday night opening and its Monday tail are not Saturday's
                Arguments.of(nySaturday, "2026-03-09T03:30:00Z", false),
                Arguments.of(nySaturday, "2026-03-09T05:00:00Z", false),
                // DST fall back: both occurrences of 01:30 on 2026-11-01
                // (EDT 05:30Z, EST 06:30Z) are the tail of Saturday Oct 31
                Arguments.of(nySaturday, "2026-11-01T05:30:00Z", true),
                Arguments.of(nySaturday, "2026-11-01T06:30:00Z", true));
    }

    @ParameterizedTest(name = "{1} -> {2}")
    @MethodSource("overnightCases")
    void overnightWindowUsesOpeningWeekday(String ruleJson, String instant, boolean expected) {
        MatchRule rule = MatchRule.parse(ruleJson);
        assertEquals(expected, rule.matches("desktop", "windows", null, Instant.parse(instant)),
                () -> ruleJson + " @ " + instant);
    }

    @Test
    void openingDayShiftAppliesOnlyToOvernightWindows() {
        // 00:00-02:00 is NOT overnight (from < to): the tail shift must not
        // leak in — Tuesday 01:00 is judged by Tuesday, not Monday.
        MatchRule dayWindow = MatchRule.parse(
                "{\"timeWindows\":[{\"from\":\"00:00\",\"to\":\"02:00\",\"tz\":\"UTC\",\"weekdays\":[2]}]}");
        assertTrue(dayWindow.matches("desktop", "windows", null, Instant.parse("2026-01-06T01:00:00Z")));
        assertFalse(dayWindow.matches("desktop", "windows", null, Instant.parse("2026-01-05T01:00:00Z")));
        // An overnight window without a weekday list is untouched by the shift.
        MatchRule noWeekdays = MatchRule.parse(
                "{\"timeWindows\":[{\"from\":\"23:00\",\"to\":\"02:00\",\"tz\":\"UTC\"}]}");
        assertTrue(noWeekdays.matches("desktop", "windows", null, Instant.parse("2026-01-06T01:00:00Z")));
    }

    @Test
    void emptyRuleConstrainsNothing() {
        // S1 gate input: "{}" parses to a rule object but pins no dimension,
        // and empty arrays normalize away — neither is conditional routing.
        assertFalse(MatchRule.parse("{}").isConstraining());
        assertFalse(MatchRule.parse("{\"devices\":[]}").isConstraining());
        assertTrue(MatchRule.parse("{\"devices\":[\"mobile\"]}").isConstraining());
        assertTrue(MatchRule.parse(
                "{\"timeWindows\":[{\"from\":\"09:00\",\"to\":\"17:00\",\"tz\":\"UTC\"}]}").isConstraining());
        // A corrupt stored rule degrades to wildcard on the read path.
        assertNull(MatchRule.parseLenient("{not json"));
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
