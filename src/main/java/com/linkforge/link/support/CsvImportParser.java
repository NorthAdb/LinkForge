package com.linkforge.link.support;

import com.linkforge.common.exception.BizException;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC4180-ish CSV parser for bulk import: quoted fields with doubled-quote escapes,
 * embedded commas/newlines/quotes, LF / CRLF / lone-CR record separators, one leading
 * UTF-8 BOM. Pure static, zero dependencies (progress pins hand-rolled over OpenCSV).
 * A record reports the 1-based FILE line it starts on (header = 1), which is what
 * import failure rows surface (D-C3-18). Fully blank lines yield no record; an
 * unclosed quote is a 400 before anything is written.
 */
public final class CsvImportParser {

    private CsvImportParser() {
    }

    public record ParsedCsv(List<String> header, List<CsvRow> rows) {
    }

    public record CsvRow(int fileLine, List<String> cells) {
    }

    public static ParsedCsv parse(String text) {
        String src = text.startsWith("\uFEFF") ? text.substring(1) : text;
        // One synthetic LF routes a final record without a trailing newline through the
        // same end-of-record path; inside quotes it just keeps inQuotes true (the 400).
        src = src + '\n';
        List<String> header = null;
        List<CsvRow> rows = new ArrayList<>();
        List<String> cells = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        int line = 1;
        int recordStartLine = 1;
        int quoteStartLine = 1;
        boolean inQuotes = false;
        boolean quoted = false;
        boolean started = false;

        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < src.length() && src.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
                continue;
            }
            if (c == ',') {
                cells.add(field.toString());
                field.setLength(0);
                started = true;
            } else if (c == '"') {
                inQuotes = true;
                quoteStartLine = line;
                quoted = true;
                started = true;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < src.length() && src.charAt(i + 1) == '\n') {
                    i++;
                }
                line++;
                cells.add(field.toString());
                field.setLength(0);
                // An unquoted record holding only empty cells is a blank line, not a row.
                boolean blank = !quoted && cells.stream().allMatch(String::isEmpty);
                if (started && !blank) {
                    if (header == null) {
                        header = cells;
                    } else {
                        rows.add(new CsvRow(recordStartLine, cells));
                    }
                    cells = new ArrayList<>();
                } else {
                    cells.clear();
                }
                quoted = false;
                started = false;
                recordStartLine = line;
            } else {
                field.append(c);
                started = true;
            }
        }
        if (inQuotes) {
            throw new BizException(400, "unclosed quoted field starting at file line " + quoteStartLine);
        }
        return new ParsedCsv(header == null ? List.of() : header, rows);
    }
}
