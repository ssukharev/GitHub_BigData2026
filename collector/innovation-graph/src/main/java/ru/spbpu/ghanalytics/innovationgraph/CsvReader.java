package ru.spbpu.ghanalytics.innovationgraph;

import java.util.ArrayList;
import java.util.List;

/** Minimal RFC 4180 reader: quoted fields, doubled quotes, LF or CRLF, optional BOM. Blank lines are skipped. */
final class CsvReader {

    private CsvReader() {
    }

    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;

        int start = text.startsWith("﻿") ? 1 : 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c != '"') {
                    field.append(c);
                } else if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = false;
                }
            } else if (c == '"' && field.isEmpty()) {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n') {
                endRow(rows, row, field);
                row = new ArrayList<>();
            } else if (c != '\r') {
                field.append(c);
            }
        }
        if (quoted) {
            throw new InnovationGraphException("CSV ends inside a quoted field");
        }
        endRow(rows, row, field);
        return rows;
    }

    private static void endRow(List<List<String>> rows, List<String> row, StringBuilder field) {
        if (row.isEmpty() && field.isEmpty()) {
            return;
        }
        row.add(field.toString());
        field.setLength(0);
        rows.add(row);
    }
}
