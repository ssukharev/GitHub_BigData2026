package ru.spbstu.ghanalytics.gharchive;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A ClickHouse answer in {@code TSVWithNames}: column positions by name, rows as string arrays,
 * and how many rows the server read for it (what the quota was charged).
 *
 * @param columns  column name to position in a row
 * @param rows     values in TSV text form; {@code \N} is NULL
 * @param rowsRead {@code read_rows} from {@code X-ClickHouse-Summary}, 0 if the server did not send it
 */
public record QueryResult(Map<String, Integer> columns, List<String[]> rows, long rowsRead) {

    /** TSV text for NULL. */
    static final String NULL = "\\N";

    /** Raw text value of the column in the row. */
    public String get(String[] row, String column) {
        Integer i = columns.get(column);
        if (i == null) {
            throw new IllegalArgumentException("no column " + column + ", have " + columns.keySet());
        }
        return row[i];
    }

    public long getLong(String[] row, String column) {
        return Long.parseLong(get(row, column));
    }

    /** {@code \N} in TSV means NULL. */
    public Long getLongOrNull(String[] row, String column) {
        String v = get(row, column);
        return NULL.equals(v) ? null : Long.valueOf(v);
    }

    /** ClickHouse prints booleans as {@code 1}/{@code 0} (UInt8) or {@code true}/{@code false} (Bool). */
    public boolean getBoolean(String[] row, String column) {
        String v = get(row, column);
        return switch (v) {
            case "1", "true" -> true;
            case "0", "false" -> false;
            default -> throw new IllegalArgumentException("column " + column + " is not a boolean: '" + v + "'");
        };
    }

    /**
     * TSVWithNames: the first line holds column names, then values separated by tabs.
     * Every row must have as many values as the header: a short line means a broken answer
     * (for example an error the server wrote after it had started sending rows).
     *
     * @throws IllegalArgumentException for an empty body or a row of the wrong width
     */
    static QueryResult parseTsv(String body, long rowsRead) {
        List<String> lines = body.lines().toList();            // no empty tail after the last \n
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("empty answer: expected TSVWithNames with a header");
        }
        String[] header = lines.get(0).split("\t", -1);
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < header.length; i++) {
            columns.put(header[i], i);
        }
        List<String[]> rows = new ArrayList<>(lines.size() - 1);
        for (int n = 1; n < lines.size(); n++) {
            String[] row = lines.get(n).split("\t", -1);
            if (row.length != header.length) {
                throw new IllegalArgumentException("line " + (n + 1) + " has " + row.length + " values, header has "
                        + header.length + ": " + abbreviate(lines.get(n)));
            }
            rows.add(row);
        }
        return new QueryResult(columns, rows, rowsRead);
    }

    private static String abbreviate(String line) {
        return line.length() <= 200 ? line : line.substring(0, 200) + "...";
    }
}
