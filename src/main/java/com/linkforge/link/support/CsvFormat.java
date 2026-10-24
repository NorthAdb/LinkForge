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
}
