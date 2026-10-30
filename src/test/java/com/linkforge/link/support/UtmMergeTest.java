package com.linkforge.link.support;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * D-C1-6/7/8 merge rules, pinned as a table: canonical append order, never
 * overwrite an exact param name already in the raw query, fragment re-attached
 * after the appended query, byte-identical identity when nothing is appended,
 * and null past the 2048 column cap.
 */
class UtmMergeTest {

    private static Map<String, String> utm(String... kv) {
        Map<String, String> utm = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            utm.put(kv[i], kv[i + 1]);
        }
        return utm;
    }

    @Test
    void appendsMissingKeysInCanonicalOrder() {
        assertEquals(
                "https://t.example.com/landing?utm_source=news&utm_medium=cpc"
                        + "&utm_campaign=launch&utm_term=kw&utm_content=banner-a",
                UtmMerger.merge("https://t.example.com/landing",
                        utm("utm_content", "banner-a", "utm_source", "news", "utm_medium", "cpc",
                                "utm_campaign", "launch", "utm_term", "kw")),
                "map iteration order must not matter; the five keys append in canonical order");
    }

    @Test
    void existingExactNamesAreNeverOverwritten() {
        assertEquals("https://t.example.com/x?utm_source=keep&utm_campaign=spring",
                UtmMerger.merge("https://t.example.com/x?utm_source=keep",
                        utm("utm_source", "fresh", "utm_campaign", "spring")));
    }

    @Test
    void nameMatchIsExactAndCaseSensitive() {
        assertEquals("https://t.example.com/x?not_utmsource=1&utm_source=fresh",
                UtmMerger.merge("https://t.example.com/x?not_utmsource=1",
                        utm("utm_source", "fresh")),
                "a longer name containing the key is not a match");
        assertEquals("https://t.example.com/x?UTM_SOURCE=upper&utm_source=fresh",
                UtmMerger.merge("https://t.example.com/x?UTM_SOURCE=upper",
                        utm("utm_source", "fresh")),
                "the raw-query check is case-sensitive");
    }

    @Test
    void fragmentReattachesAfterTheAppendedQuery() {
        assertEquals("https://t.example.com/p?a=1&utm_campaign=c#sec",
                UtmMerger.merge("https://t.example.com/p?a=1#sec", utm("utm_campaign", "c")));
        assertEquals("https://t.example.com/p?utm_source=s#frag?q-not",
                UtmMerger.merge("https://t.example.com/p#frag?q-not", utm("utm_source", "s")),
                "a ? inside the fragment must not become the query separator");
    }

    @Test
    void noUtmMeansByteIdenticalIdentity() {
        String url = "https://t.example.com/x?a=1#f";
        assertSame(url, UtmMerger.merge(url, null));
        assertSame(url, UtmMerger.merge(url, Map.of()));
        assertSame(url, UtmMerger.merge(url, utm("utm_source", " ")),
                "blank values are absent");
        String covered = "https://t.example.com/x?utm_source=keep#f";
        assertSame(covered, UtmMerger.merge(covered, utm("utm_source", "other")),
                "an already-present name appends nothing, so the input is returned as-is");
    }

    @Test
    void urlWithoutQueryGetsTheQuestionMark() {
        assertEquals("https://t.example.com/p?utm_medium=email",
                UtmMerger.merge("https://t.example.com/p", utm("utm_medium", "email")));
    }

    @Test
    void mergePastTheColumnCapReturnsNull() {
        String longUrl = "https://t.example.com/" + "a".repeat(2020);
        assertEquals(longUrl, UtmMerger.merge(longUrl, Map.of()),
                "2042 chars alone fit and stay unchanged");
        assertNull(UtmMerger.merge(longUrl, utm("utm_source", "abcdefgh")),
                "?utm_source=abcdefgh pushes past 2048");
        String atCap = "https://t.example.com/" + "a".repeat(2012) + "?utm_source=ok";
        assertEquals(atCap, UtmMerger.merge("https://t.example.com/" + "a".repeat(2012),
                        utm("utm_source", "ok")),
                "exactly 2048 is not over the cap");
        assertNull(UtmMerger.merge("https://t.example.com/" + "a".repeat(2013),
                        utm("utm_source", "ok")),
                "2049 is over the cap");
    }
}
