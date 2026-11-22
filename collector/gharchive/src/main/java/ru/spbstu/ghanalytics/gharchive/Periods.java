package ru.spbstu.ghanalytics.gharchive;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Splits a period into the pieces the collector queries. One calendar year per query keeps every
 * query at 2-6 s, far below the 60 s limit of the playground ({@code max_execution_time}), and below
 * 1 000 000 result rows ({@code max_result_rows}); the whole period in one query would come close
 * to the time limit for {@code monthly} and break both limits for {@code hourly}.
 */
public final class Periods {

    /** A piece of a period; both ends inclusive. */
    public record Chunk(LocalDate from, LocalDate to) {
        public Chunk {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            if (from.isAfter(to)) {
                throw new IllegalArgumentException("chunk from " + from + " is after to " + to);
            }
        }

        @Override
        public String toString() {
            return from + ".." + to;
        }
    }

    private Periods() {
    }

    /** [from, to] by calendar years; the first and the last piece may be partial. */
    public static List<Chunk> byYear(LocalDate from, LocalDate to) {
        check(from, to);
        List<Chunk> chunks = new ArrayList<>();
        for (int year = from.getYear(); year <= to.getYear(); year++) {
            LocalDate start = LocalDate.of(year, 1, 1);
            LocalDate end = LocalDate.of(year, 12, 31);
            chunks.add(new Chunk(start.isBefore(from) ? from : start, end.isAfter(to) ? to : end));
        }
        return chunks;
    }

    /**
     * The longest piece of whole months inside [from, to], or empty. A monthly query over a part of
     * a month would write a partial month over a full one, so monthly tasks take whole months only.
     *
     * @throws IllegalArgumentException if from is after to
     */
    public static Optional<Chunk> fullMonths(LocalDate from, LocalDate to) {
        check(from, to);
        YearMonth first = from.getDayOfMonth() == 1 ? YearMonth.from(from) : YearMonth.from(from).plusMonths(1);
        YearMonth last = to.equals(YearMonth.from(to).atEndOfMonth()) ? YearMonth.from(to) : YearMonth.from(to).minusMonths(1);
        if (first.isAfter(last)) {
            return Optional.empty();
        }
        return Optional.of(new Chunk(first.atDay(1), last.atEndOfMonth()));
    }

    /** Value of {@code {from:DateTime}}: {@code 2024-03-06 00:00:00}. */
    public static String startOf(LocalDate from) {
        return from + " 00:00:00";
    }

    /** Value of {@code {to:DateTime}}: the exclusive end, midnight of the next day. */
    public static String endExclusive(LocalDate to) {
        return to.plusDays(1) + " 00:00:00";
    }

    private static void check(LocalDate from, LocalDate to) {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from " + from + " is after to " + to);
        }
    }
}
