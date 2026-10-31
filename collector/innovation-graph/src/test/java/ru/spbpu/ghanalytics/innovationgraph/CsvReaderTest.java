package ru.spbpu.ghanalytics.innovationgraph;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CsvReaderTest {

    @Test
    void readsPlainRows() {
        assertEquals(List.of(List.of("a", "b"), List.of("1", "2")), CsvReader.parse("a,b\n1,2\n"));
    }

    @Test
    void handlesCrlfBomAndMissingFinalNewline() {
        assertEquals(List.of(List.of("a", "b"), List.of("1", "2")), CsvReader.parse("﻿a,b\r\n1,2"));
    }

    @Test
    void skipsBlankLines() {
        assertEquals(List.of(List.of("a"), List.of("1")), CsvReader.parse("a\n\n1\n\n"));
    }

    @Test
    void readsQuotedFields() {
        assertEquals(List.of(List.of("x, y", "say \"hi\"", "two\nlines")),
                CsvReader.parse("\"x, y\",\"say \"\"hi\"\"\",\"two\nlines\"\n"));
    }

    @Test
    void keepsEmptyFields() {
        assertEquals(List.of(List.of("", "b", "")), CsvReader.parse(",b,\n"));
    }

    @Test
    void unterminatedQuoteThrows() {
        assertThrows(InnovationGraphException.class, () -> CsvReader.parse("a,\"b\n"));
    }
}
