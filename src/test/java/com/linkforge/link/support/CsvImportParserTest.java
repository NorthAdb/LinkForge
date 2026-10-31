package com.linkforge.link.support;

import com.linkforge.common.exception.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Pure unit tests for the hand-rolled import parser (no Spring, no Docker). */
class CsvImportParserTest {

    @Test
    void stripsLeadingBom() {
        var parsed = CsvImportParser.parse("\uFEFForiginal_url\nhttps://a.com/x\n");
        assertEquals(List.of("original_url"), parsed.header());
        assertEquals(1, parsed.rows().size());
        assertEquals(2, parsed.rows().get(0).fileLine());
        assertEquals(List.of("https://a.com/x"), parsed.rows().get(0).cells());
    }

    @Test
    void handlesCrlfAndLf() {
        var parsed = CsvImportParser.parse("original_url,title\r\nhttps://a.com/1,A\r\nhttps://a.com/2,B\n");
        assertEquals(2, parsed.rows().size());
        assertEquals(2, parsed.rows().get(0).fileLine());
        assertEquals(3, parsed.rows().get(1).fileLine());
        assertEquals(List.of("https://a.com/2", "B"), parsed.rows().get(1).cells());
    }

    @Test
    void quotedFieldsSurviveCommasQuotesAndEmbeddedNewlines() {
        var parsed = CsvImportParser.parse(
                "original_url,title\n"
                        + "\"https://a.com/x?utm=a,b\",\"say \"\"hi\"\"\"\n"
                        + "\"https://a.com/y\",\"line1\nline2\"\n"
                        + "https://a.com/z,Z\n");
        assertEquals(3, parsed.rows().size());
        assertEquals(List.of("https://a.com/x?utm=a,b", "say \"hi\""), parsed.rows().get(0).cells());
        assertEquals(2, parsed.rows().get(0).fileLine());
        // The quoted newline consumes file line 3, so the next record starts on line 4.
        assertEquals(List.of("https://a.com/y", "line1\nline2"), parsed.rows().get(1).cells());
        assertEquals(3, parsed.rows().get(1).fileLine());
        assertEquals(5, parsed.rows().get(2).fileLine());
    }

    @Test
    void unknownColumnsPassThroughForTheCallerToIgnore() {
        var parsed = CsvImportParser.parse("original_url,code,status\nhttps://a.com/x,abc,1\n");
        assertEquals(List.of("original_url", "code", "status"), parsed.header());
        assertEquals(List.of("https://a.com/x", "abc", "1"), parsed.rows().get(0).cells());
    }

    @Test
    void fullyBlankLinesYieldNoRowAndDoNotAdvanceRowNumbers() {
        var parsed = CsvImportParser.parse("original_url\n\nhttps://a.com/x\n\r\n\nhttps://a.com/y\n");
        assertEquals(2, parsed.rows().size());
        assertEquals(3, parsed.rows().get(0).fileLine());
        assertEquals(6, parsed.rows().get(1).fileLine());
    }

    @Test
    void lastRecordWithoutTrailingNewlineStillParses() {
        var parsed = CsvImportParser.parse("original_url\nhttps://a.com/x");
        assertEquals(1, parsed.rows().size());
        assertEquals(2, parsed.rows().get(0).fileLine());
    }

    @Test
    void unclosedQuoteIsABadRequest() {
        assertThrows(BizException.class,
                () -> CsvImportParser.parse("original_url,title\n\"https://a.com/x,oops\n"));
    }
}
