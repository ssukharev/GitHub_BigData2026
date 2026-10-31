package ru.spbpu.ghanalytics.innovationgraph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CountryCodeReportTest {

    @Test
    void countsRowsOfUnknownCodes() {
        SortedMap<String, Long> unknown = CountryCodeReport.unknownCodes(
                List.of("US", "TW", "TW", "AQ", "US", "TW"), Set.of("US", "RU"));

        assertEquals(Map.of("TW", 3L, "AQ", 1L), unknown);
        assertEquals("AQ", unknown.firstKey());
    }

    @Test
    void nothingUnknown() {
        SortedMap<String, Long> unknown = CountryCodeReport.unknownCodes(List.of("US"), Set.of("US"));

        assertTrue(unknown.isEmpty());
        assertEquals("All country codes are known", CountryCodeReport.format(unknown));
    }

    @Test
    void formatListsEveryCode() {
        String text = CountryCodeReport.format(CountryCodeReport.unknownCodes(List.of("TW", "AQ", "TW"), Set.of()));

        assertEquals("Country codes missing from core.countries:\n  AQ: 1 rows\n  TW: 2 rows", text);
    }
}
