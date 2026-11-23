package ru.spbstu.ghanalytics.gharchive;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code marts.gh_activity_monthly_valid} (R__marts_gh_activity_monthly_valid.sql) on a real PostgreSQL 16. */
@Testcontainers
class MartsGhActivityMonthlyValidIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM core.gh_activity_hourly");
        jdbc.update("DELETE FROM core.gh_activity_monthly");
        jdbc.execute("SET TIME ZONE 'Europe/Moscow'");   // the view must not depend on the session zone
    }

    /** {@code events} pushes in every hour of [from, to), UTC. */
    private static void pushHours(String from, String to, int events) {
        jdbc.update("""
                INSERT INTO core.gh_activity_hourly (ts_hour, event_type, is_bot, events, actors, commits)
                SELECT h, 'PushEvent', false, ?, 10, ?
                FROM generate_series(?::timestamptz, ?::timestamptz - interval '1 hour', interval '1 hour') AS h
                """, events, events, from, to);
    }

    private static void monthly(String month, String type, long events, long actors) {
        jdbc.update("""
                INSERT INTO core.gh_activity_monthly (month, event_type, is_bot, events, actors, repos, commits)
                VALUES (?::date, ?, false, ?, ?, 7, CASE WHEN ? = 'PushEvent' THEN ? END)
                """, month, type, events, actors, type, events);
    }

    private static Map<String, Object> row(String month, String type) {
        return jdbc.queryForMap("SELECT * FROM marts.gh_activity_monthly_valid WHERE month = ?::date AND event_type = ?",
                month, type);
    }

    @Test
    void aCompleteMonthHasFullCoverageNoGapAndTheMonthlyNumbers() {
        pushHours("2019-01-01T00:00Z", "2019-02-01T00:00Z", 100);
        monthly("2019-01-01", "PushEvent", 74_400, 900);

        assertThat(row("2019-01-01", "PushEvent"))
                .containsEntry("events", 74_400L)
                .as("actors of the month, not a sum of hours").containsEntry("actors", 900L)
                .containsEntry("repos", 7L)
                .containsEntry("commits", 74_400L)
                .containsEntry("hours_in_month", 744L)
                .containsEntry("hours_present", 744L)
                .containsEntry("hour_coverage", new BigDecimal("1.0000"))
                .containsEntry("low_push_days", 0L)
                .containsEntry("known_gap", false);
    }

    @Test
    void aMissingDayLowersTheCoverageAndIsAKnownGap() {
        pushHours("2019-01-01T00:00Z", "2019-02-10T00:00Z", 100);
        pushHours("2019-02-11T00:00Z", "2019-03-01T00:00Z", 100);
        monthly("2019-02-01", "PushEvent", 64_800, 800);

        Map<String, Object> february = row("2019-02-01", "PushEvent");

        assertThat(february).containsEntry("hours_in_month", 672L).containsEntry("hours_present", 648L)
                .containsEntry("hour_coverage", new BigDecimal("0.9643"))
                .as("the empty day has no pushes").containsEntry("low_push_days", 1L)
                .containsEntry("known_gap", true);
        assertThat(february.get("events")).as("a hole stays a hole, nothing is filled in").isEqualTo(64_800L);
    }

    @Test
    void aDayFarBelowTheMedianIsALowPushDayEvenWithAllItsHours() {
        pushHours("2019-02-01T00:00Z", "2019-03-15T00:00Z", 100);
        pushHours("2019-03-15T00:00Z", "2019-03-16T00:00Z", 1);
        pushHours("2019-03-16T00:00Z", "2019-04-01T00:00Z", 100);
        monthly("2019-03-01", "PushEvent", 72_024, 900);

        assertThat(row("2019-03-01", "PushEvent")).containsEntry("hour_coverage", new BigDecimal("1.0000"))
                .containsEntry("low_push_days", 1L)
                .containsEntry("known_gap", true);
    }

    @Test
    void everyEventTypeOfAMonthGetsTheCoverageOfThatMonth() {
        pushHours("2019-01-01T00:00Z", "2019-02-01T00:00Z", 100);
        monthly("2019-01-01", "PushEvent", 74_400, 900);
        monthly("2019-01-01", "IssuesEvent", 500, 300);

        Map<String, Object> issues = row("2019-01-01", "IssuesEvent");

        assertThat(issues).containsEntry("hours_present", 744L).containsEntry("known_gap", false)
                .containsEntry("events", 500L);
        assertThat(issues.get("commits")).as("commits only for PushEvent").isNull();
    }

    @Test
    void onlyTheComparablePeriodIsInTheView() {
        monthly("2025-04-01", "PushEvent", 1, 1);
        monthly("2025-05-01", "PushEvent", 1, 1);
        monthly("2026-07-01", "PushEvent", 1, 1);

        List<Date> months = jdbc.queryForList("SELECT DISTINCT month FROM marts.gh_activity_monthly_valid ORDER BY 1",
                Date.class);

        assertThat(months).containsExactly(Date.valueOf(LocalDate.of(2025, 4, 1)));
    }
}
