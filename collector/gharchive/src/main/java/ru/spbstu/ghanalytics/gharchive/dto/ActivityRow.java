package ru.spbstu.ghanalytics.gharchive.dto;

import java.time.Instant;
import java.time.LocalDate;

/** One row of {@code core.gh_activity_hourly} or {@code core.gh_activity_monthly}. */
public sealed interface ActivityRow {

    /** commits is null for non-push types and from 2025-10 (NULL in the source and in the database, not 0). */
    record Hourly(Instant tsHour, String eventType, boolean isBot, long events, long actors, Long commits)
            implements ActivityRow {
    }

    /** month is the first day of the month; commits as in {@link Hourly}. */
    record Monthly(LocalDate month, String eventType, boolean isBot, long events, long actors, long repos,
                   Long commits) implements ActivityRow {
    }
}
