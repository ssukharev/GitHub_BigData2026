package ru.spbstu.ghanalytics.github;

import java.time.Duration;
import java.time.Instant;

/**
 * A window of creation time, the twin of {@link DateRange} in seconds. Both ends are included
 * and the precision is a second, as in {@code created:A..B}: windows {@code [A, A + width - 1 s]}
 * tile a day without gaps or overlaps.
 *
 * @param from first second, inclusive, in whole seconds
 * @param to   last second, inclusive, in whole seconds
 */
public record TimeWindow(Instant from, Instant to) {

    public TimeWindow {
        if (from == null || to == null) {
            throw new IllegalArgumentException("from and to are required, got from=" + from + ", to=" + to);
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from " + from + " is after to " + to);
        }
        if (from.getNano() != 0 || to.getNano() != 0) {
            throw new IllegalArgumentException("a window has whole seconds only, got " + from + ".." + to);
        }
    }

    /** Number of seconds, both ends included: 10:00:00..10:09:59 is 600, a single second is 1. */
    public long seconds() {
        return Duration.between(from, to).toSeconds() + 1;
    }

    /** Whether a creation time falls into the window, both ends included. */
    public boolean contains(Instant instant) {
        return !instant.isBefore(from) && !instant.isAfter(to);
    }

    /** {@code created:2023-03-15T10:00:00Z..2023-03-15T10:09:59Z}: ISO 8601 in UTC, no fractions. */
    public String toQualifier() {
        return "created:" + from + ".." + to;
    }
}
