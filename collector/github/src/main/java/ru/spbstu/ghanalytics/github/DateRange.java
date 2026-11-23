package ru.spbstu.ghanalytics.github;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * A range of creation dates for GitHub search, both days included, as in {@code created:A..B}.
 * A date without a time means the whole day in UTC.
 *
 * @param from first day, inclusive
 * @param to   last day, inclusive
 */
public record DateRange(LocalDate from, LocalDate to) {

    public DateRange {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required, got from=" + from + ", to=" + to);
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from " + from + " is after to " + to);
        }
    }

    /** Number of days, both ends included: 03-01..03-01 is 1, 03-01..03-31 is 31. */
    public long days() {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    public boolean isSingleDay() {
        return from.equals(to);
    }

    /**
     * Two halves without a gap or an overlap: the left one is not longer than the right one
     * (31 days give 15 + 16, 2 days give 1 + 1).
     *
     * @throws IllegalStateException for a single day, which cannot be split
     */
    public List<DateRange> split() {
        if (isSingleDay()) {
            throw new IllegalStateException("a single day " + from + " cannot be split");
        }
        LocalDate leftEnd = from.plusDays(days() / 2 - 1);
        return List.of(new DateRange(from, leftEnd), new DateRange(leftEnd.plusDays(1), to));
    }

    /** {@code created:2021-03-01..2021-03-31}. */
    public String toQualifier() {
        return "created:" + from + ".." + to;
    }
}
