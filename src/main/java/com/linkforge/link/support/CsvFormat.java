package com.linkforge.link.support;

/** RFC4180-ish CSV field escaping, unit-tested without Spring. */
public final class CsvFormat {

    private CsvFormat() {
    }

    public static String escape(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    /**
     * M8 (audit batches 1-3): like {@link #escape}, but first neutralizes
     * spreadsheet formula injection for attacker/owner-influenced text cells.
     * If the first character that is not a space/tab/CR/LF is one of
     * {@code = + - @}, the ORIGINAL value is prefixed with a single quote
     * (spreadsheets render it as text, never as a formula) and then RFC-escaped
     * as usual. Any other value is byte-identical to {@link #escape}. The
     * detection runs on the raw text, never on an already-quoted field, so a
     * dangerous prefix cannot hide behind CSV quoting.
     */
    public static String escapeSpreadsheet(String value) {
        if (value == null || value.isEmpty()) {
            return escape(value);
        }
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                i++;
            } else {
                break;
            }
        }
        if (i < value.length()) {
            char first = value.charAt(i);
            if (first == '=' || first == '+' || first == '-' || first == '@') {
                return escape("'" + value);
            }
        }
        return escape(value);
    }
}
