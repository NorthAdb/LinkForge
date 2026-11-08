package com.linkforge.link.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CsvFormatTest {

    /** Minimal single-field CSV parse: undo the quoting escape() applies. */
    private static String parseCell(String escaped) {
        if (escaped.length() >= 2 && escaped.startsWith("\"") && escaped.endsWith("\"")) {
            return escaped.substring(1, escaped.length() - 1).replace("\"\"", "\"");
        }
        return escaped;
    }

    @Test
    void plainValuesPassThrough() {
        assertEquals("abc", CsvFormat.escape("abc"));
        assertEquals("https://example.com/x?y=1", CsvFormat.escape("https://example.com/x?y=1"));
    }

    @Test
    void nullAndEmptyBecomeEmpty() {
        assertEquals("", CsvFormat.escape(null));
        assertEquals("", CsvFormat.escape(""));
    }

    @Test
    void commasQuotesAndNewlinesAreWrappedAndDoubled() {
        assertEquals("\"a,b\"", CsvFormat.escape("a,b"));
        assertEquals("\"a\"\"b\"", CsvFormat.escape("a\"b"));
        assertEquals("\"a\nb\"", CsvFormat.escape("a\nb"));
        assertEquals("\"a\r\nb,\"\"c\"\"\"", CsvFormat.escape("a\r\nb,\"c\""));
    }

    @Test
    void spreadsheetPrefixesAreNeutralized() {
        // M8: a leading = + - @ (after any space/tab/CR/LF padding) would be
        // executed as a formula by spreadsheet apps; the parsed cell must
        // start with a single quote prefixing the ORIGINAL text.
        List<String> dangerous = List.of(
                "=1+1", "+1", "-1", "@SUM(1,1)",
                " =1+1", "\t=1+1", "\r\n@x", "  -2+2");
        for (String value : dangerous) {
            String cell = parseCell(CsvFormat.escapeSpreadsheet(value));
            assertEquals("'" + value, cell, "dangerous value must be quote-prefixed: " + value);
            assertTrue(cell.startsWith("'"), cell);
        }
        // Normal text is untouched — and for every non-dangerous input the
        // spreadsheet variant is byte-identical to plain escape().
        List<String> normal = List.of(
                "hello", "a,b", "say \"hi\"", "line1\nline2",
                "Mozilla/5.0 (Windows NT)", "https://example.com/?a=1+2",
                "x=1", "a@b", "100", " note -1");
        for (String value : normal) {
            assertEquals(value, parseCell(CsvFormat.escapeSpreadsheet(value)),
                    "normal value must pass through: " + value);
            assertEquals(CsvFormat.escape(value), CsvFormat.escapeSpreadsheet(value),
                    "non-dangerous input must equal escape(): " + value);
        }
        assertEquals("", CsvFormat.escapeSpreadsheet(null));
        assertEquals("", CsvFormat.escapeSpreadsheet(""));
    }
}
