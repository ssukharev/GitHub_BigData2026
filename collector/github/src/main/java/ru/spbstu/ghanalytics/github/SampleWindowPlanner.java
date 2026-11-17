package ru.spbstu.ghanalytics.github;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.SplittableRandom;

/**
 * Plan of the stratified sample: calendar months of creation are the strata, and each month is
 * cut into windows of a fixed width, put in a random order by a seed. The collector takes
 * windows in this order, every repository of a window, until the month quota is reached.
 * <p>
 * A pure function of the period, the seed and the width: the plan is never stored, a run with
 * {@code --resume} builds the same order again. The randomness is ours, not GitHub's: the order
 * of search results is stars descending and must never decide what is sampled.
 */
public class SampleWindowPlanner {

    /**
     * Chosen for {@code --min-stars=10}: 4-5 repositories in a window on average, 13 at most,
     * one page of search. For a higher threshold take a wider window (stars >= 50: 30 minutes,
     * stars >= 100: 60 minutes), aiming at 3-6 repositories per window.
     */
    public static final Duration DEFAULT_WIDTH = Duration.ofMinutes(10);

    static final long SECONDS_PER_DAY = 86_400;
    /** Spreads the seeds of neighbouring months; SplittableRandom mixes the seed itself. */
    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private final long seed;
    private final long widthSeconds;

    /**
     * @param width a whole number of minutes that divides the day (5, 10, 15, 30, 60, ...)
     * @throws IllegalArgumentException for any other width
     */
    public SampleWindowPlanner(long seed, Duration width) {
        Objects.requireNonNull(width, "width");
        long seconds = width.toSeconds();
        if (seconds < 60 || seconds % 60 != 0 || SECONDS_PER_DAY % seconds != 0 || width.toNanosPart() != 0) {
            throw new IllegalArgumentException("window width must be whole minutes dividing the day, got " + width);
        }
        this.seed = seed;
        this.widthSeconds = seconds;
    }

    public long seed() {
        return seed;
    }

    public Duration width() {
        return Duration.ofSeconds(widthSeconds);
    }

    /** The period cut by calendar months; the first and the last month are clipped by the period. */
    public static List<DateRange> strata(LocalDate from, LocalDate to) {
        DateRange period = new DateRange(from, to);   // validates the bounds
        List<DateRange> out = new ArrayList<>();
        for (YearMonth m = YearMonth.from(period.from()); !m.isAfter(YearMonth.from(period.to())); m = m.plusMonths(1)) {
            LocalDate start = m.atDay(1).isBefore(period.from()) ? period.from() : m.atDay(1);
            LocalDate end = m.atEndOfMonth().isAfter(period.to()) ? period.to() : m.atEndOfMonth();
            out.add(new DateRange(start, end));
        }
        return out;
    }

    /**
     * Every window of the stratum, in UTC from 00:00:00 of its first day to 23:59:59 of its last
     * day, in a random order given by the seed and the stratum. Windows of local time would move
     * the grid by the time zone of the machine.
     */
    public List<TimeWindow> windows(DateRange stratum) {
        Instant start = stratum.from().atStartOfDay(ZoneOffset.UTC).toInstant();
        long count = stratum.days() * SECONDS_PER_DAY / widthSeconds;
        List<TimeWindow> all = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            Instant from = start.plusSeconds(i * widthSeconds);
            all.add(new TimeWindow(from, from.plusSeconds(widthSeconds - 1)));
        }
        // not seed + month number: neighbouring seeds of java.util.Random give similar first numbers
        Collections.shuffle(all, new SplittableRandom(seed + GOLDEN_GAMMA * stratum.from().toEpochDay()));
        return all;
    }
}
