package ru.spbstu.ghanalytics.gharchive;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import ru.spbstu.ghanalytics.gharchive.dto.ActivityRow;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;

/**
 * Upserts into {@code core.gh_activity_hourly} and {@code core.gh_activity_monthly}: a row with the same key
 * is updated, not added again, so loading the same period twice changes nothing. Every column is
 * overwritten, {@code commits} too, including with NULL. One call is one transaction: a task of
 * the collector stores all its rows or none.
 */
@Repository
public class ActivityRepository {

    /** Rows per JDBC batch. */
    static final int BATCH_SIZE = 5000;

    private static final String UPSERT_HOURLY = """
            INSERT INTO core.gh_activity_hourly (ts_hour, event_type, is_bot, events, actors, commits)
            VALUES (:tsHour, :eventType, :isBot, :events, :actors, :commits)
            ON CONFLICT (ts_hour, event_type, is_bot) DO UPDATE
                SET events = EXCLUDED.events, actors = EXCLUDED.actors, commits = EXCLUDED.commits""";

    private static final String UPSERT_MONTHLY = """
            INSERT INTO core.gh_activity_monthly (month, event_type, is_bot, events, actors, repos, commits)
            VALUES (:month, :eventType, :isBot, :events, :actors, :repos, :commits)
            ON CONFLICT (month, event_type, is_bot) DO UPDATE
                SET events = EXCLUDED.events, actors = EXCLUDED.actors, repos = EXCLUDED.repos,
                    commits = EXCLUDED.commits""";

    private final NamedParameterJdbcTemplate jdbc;

    public ActivityRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** ON CONFLICT (ts_hour, event_type, is_bot) DO UPDATE events, actors, commits. Returns the number of rows. */
    @Transactional
    public int upsertHourly(List<ActivityRow.Hourly> rows) {
        return upsert(UPSERT_HOURLY, rows, r -> new MapSqlParameterSource()
                .addValue("tsHour", OffsetDateTime.ofInstant(r.tsHour(), ZoneOffset.UTC))   // PgJDBC takes no Instant
                .addValue("eventType", r.eventType())
                .addValue("isBot", r.isBot())
                .addValue("events", r.events())
                .addValue("actors", r.actors())
                .addValue("commits", r.commits(), Types.BIGINT));
    }

    /** ON CONFLICT (month, event_type, is_bot) DO UPDATE events, actors, repos, commits. Returns the number of rows. */
    @Transactional
    public int upsertMonthly(List<ActivityRow.Monthly> rows) {
        return upsert(UPSERT_MONTHLY, rows, r -> new MapSqlParameterSource()
                .addValue("month", r.month())
                .addValue("eventType", r.eventType())
                .addValue("isBot", r.isBot())
                .addValue("events", r.events())
                .addValue("actors", r.actors())
                .addValue("repos", r.repos())
                .addValue("commits", r.commits(), Types.BIGINT));
    }

    private <T> int upsert(String sql, List<T> rows, Function<T, SqlParameterSource> params) {
        for (int start = 0; start < rows.size(); start += BATCH_SIZE) {
            List<T> batch = rows.subList(start, Math.min(rows.size(), start + BATCH_SIZE));
            jdbc.batchUpdate(sql, batch.stream().map(params).toArray(SqlParameterSource[]::new));
        }
        return rows.size();
    }
}
