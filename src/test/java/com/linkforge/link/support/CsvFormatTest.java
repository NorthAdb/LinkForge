package com.linkforge.link.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CsvFormatTest {

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
}
