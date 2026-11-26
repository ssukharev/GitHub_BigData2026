package ru.spbstu.ghanalytics.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.tablesaw.api.Row;
import tech.tablesaw.api.Table;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 1 queries on the real repeatable view, migrated into isolated PostgreSQL. */
class GhArchiveSqlQueriesTest {

    private DbConfig db;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDatabase.start();
        TestDatabase.execute(db, """
                TRUNCATE core.gh_activity_hourly, core.gh_activity_monthly;
                INSERT INTO core.gh_activity_hourly
                SELECT h, 'PushEvent', false, 10, 5, 20
                FROM generate_series(timestamptz '2018-01-01 00:00+00',
                                     timestamptz '2018-01-31 23:00+00', interval '1 hour') AS h;
                INSERT INTO core.gh_activity_monthly VALUES
                    ('2017-12-01', 'PushEvent', false, 999, 100, 50, 777),
                    ('2018-01-01', 'PushEvent', false, 100, 20, 30, 200),
                    ('2018-01-01', 'PushEvent', true, 0, 0, 0, 0),
                    ('2018-01-01', 'CreateEvent', false, 500, 100, 50, NULL),
                    ('2018-01-01', 'CreateEvent:repository', false, 7, 4, 7, NULL),
                    ('2018-02-01', 'PushEvent', false, 5, 2, 2, NULL),
                    ('2025-04-01', 'PushEvent', false, 0, 0, 0, NULL),
                    ('2025-04-01', 'PushEvent', true, 0, 0, 0, NULL),
                    ('2025-05-01', 'PushEvent', false, 999, 100, 50, 777);
                """);
    }

    private Table query() throws SQLException {
        try (Connection conn = db.open()) {
            return Sql.table(conn, "task1/gharchive_monthly");
        }
    }

    @Test
    void calendarGridKeepsTheComparableRangeAndNeverAddsCreateEventTwice() throws SQLException {
        Table rows = query();
        assertEquals(88, rows.rowCount());
        assertEquals(LocalDate.of(2018, 1, 1), rows.dateColumn("month").get(0));
        assertEquals(LocalDate.of(2025, 4, 1), rows.dateColumn("month").get(87));
        Row jan = rows.row(0);
        assertEquals("observed", jan.getString("status"));
        assertEquals(744, jan.getLong("hours_present"));
        assertEquals(100, jan.getLong("pushes_nonbot"));
        assertEquals(20, jan.getLong("push_actors_nonbot"), "monthly count, not summed hourly actors");
        assertEquals(30, jan.getLong("push_repos_nonbot"), "published monthly distinct repositories");
        assertEquals(200, jan.getLong("commits_proxy_nonbot"));
        assertEquals(7, jan.getLong("new_repos_nonbot"), "only CreateEvent:repository");
        assertEquals(0, jan.getLong("pushes_bot"));
        assertFalse(rows.column("pushes_bot").isMissing(0), "published zero remains zero");
        assertTrue(rows.column("new_repos_bot").isMissing(0), "unpublished group is not a zero");
        assertEquals(0.0, jan.getDouble("bot_push_share_pct"), 1e-9);
    }

    @Test
    void gapValuesAreKeptForAuditAndEntirelyMissingMonthsAreNotZeros() throws SQLException {
        Table rows = query();
        Row feb = rows.row(1);
        assertEquals("known_gap", feb.getString("status"));
        assertEquals(5, feb.getLong("pushes_nonbot"));
        assertTrue(rows.column("commits_proxy_nonbot").isMissing(1));
        assertTrue(rows.column("bot_push_share_pct").isMissing(1), "both groups required");
        assertEquals("missing_month", rows.row(2).getString("status"));
        assertTrue(rows.column("pushes_nonbot").isMissing(2));
        assertTrue(rows.column("hours_present").isMissing(2));
        assertTrue(rows.column("bot_push_share_pct").isMissing(87), "undefined for zero denominator");
    }
}
