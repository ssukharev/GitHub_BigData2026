package ru.spbstu.ghanalytics.analytics.task1;

import org.junit.jupiter.api.Test;
import tech.tablesaw.api.DateColumn;
import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GhArchiveComparisonTest {

    @Test
    void matchesCalendarMonthsAndExcludesBothSidesOfAnAffectedPair() {
        Table rows = Table.create("monthly",
                DateColumn.create("month"), StringColumn.create("status"), DoubleColumn.create("value"));
        Map<YearMonth, Integer> index = new HashMap<>();
        add(rows, index, "2024-01", "observed", 100);
        add(rows, index, "2024-02", "observed", 5000);
        add(rows, index, "2024-03", "observed", 50);
        add(rows, index, "2024-04", "observed", 60);
        add(rows, index, "2025-01", "observed", 200);
        add(rows, index, "2025-02", "known_gap", 10);
        add(rows, index, "2025-03", "observed", Double.NaN);
        // April 2025 is completely absent; shifting March or February to fill it is forbidden.
        Task1GhArchive.Comparison c = Task1GhArchive.compare(rows, index,
                new Task1GhArchive.Window("test", YearMonth.of(2025, 1), 4), "value");
        assertEquals(1, c.matchedPairs());
        assertEquals(1, c.gapPairs());
        assertEquals(2, c.missingPairs());
        assertEquals(100.0, c.meanBefore(), 1e-9);
        assertEquals(200.0, c.meanAfter(), 1e-9);
        assertEquals(100.0, c.relativeChangePct(), 1e-9);
    }

    @Test
    void publishedZeroIsUsableButGrowthFromZeroIsUndefined() {
        Table rows = Table.create("monthly",
                DateColumn.create("month"), StringColumn.create("status"), DoubleColumn.create("value"));
        Map<YearMonth, Integer> index = new HashMap<>();
        add(rows, index, "2024-01", "observed", 0);
        add(rows, index, "2025-01", "observed", 10);
        Task1GhArchive.Comparison c = Task1GhArchive.compare(rows, index,
                new Task1GhArchive.Window("test", YearMonth.of(2025, 1), 1), "value");
        assertEquals(1, c.matchedPairs());
        assertEquals(10.0, c.change(), 1e-9);
        assertTrue(Double.isNaN(c.relativeChangePct()));
        Task1GhArchive.Comparison none = Task1GhArchive.compare(rows, index,
                new Task1GhArchive.Window("test", YearMonth.of(2025, 2), 1), "value");
        assertEquals(0, none.matchedPairs());
        assertTrue(Double.isNaN(none.meanBefore()));
        assertTrue(Double.isNaN(none.meanAfter()));
    }

    private static void add(Table rows, Map<YearMonth, Integer> index, String month, String status, double value) {
        YearMonth date = YearMonth.parse(month);
        index.put(date, rows.rowCount());
        rows.dateColumn("month").append(date.atDay(1));
        rows.stringColumn("status").append(status);
        rows.doubleColumn("value").append(value);
    }
}
