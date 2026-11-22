package ru.spbstu.ghanalytics.gharchive;

import ru.spbstu.ghanalytics.gharchive.dto.ActivityRow;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Rows of the activity queries (TSV text) to {@link ActivityRow}; times are UTC, as in GH Archive. */
final class ActivityMapper {

    private ActivityMapper() {
    }

    /** Columns of hourly_activity.sql: ts_hour, event_type, is_bot, events, actors, commits. */
    static List<ActivityRow.Hourly> toHourly(QueryResult r) {
        return r.rows().stream().map(row -> new ActivityRow.Hourly(
                utc(r.get(row, "ts_hour")),
                r.get(row, "event_type"),
                r.getBoolean(row, "is_bot"),
                r.getLong(row, "events"),
                r.getLong(row, "actors"),
                r.getLongOrNull(row, "commits"))).toList();
    }

    /** Columns of monthly_activity.sql and monthly_new_repos.sql: month, ..., repos, commits. */
    static List<ActivityRow.Monthly> toMonthly(QueryResult r) {
        return r.rows().stream().map(row -> new ActivityRow.Monthly(
                LocalDate.parse(r.get(row, "month")),
                r.get(row, "event_type"),
                r.getBoolean(row, "is_bot"),
                r.getLong(row, "events"),
                r.getLong(row, "actors"),
                r.getLong(row, "repos"),
                r.getLongOrNull(row, "commits"))).toList();
    }

    /** {@code 2024-01-01 00:00:00} of a server in UTC. */
    static Instant utc(String dateTime) {
        return LocalDateTime.parse(dateTime.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
    }
}
