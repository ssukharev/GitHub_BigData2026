package ru.spbstu.ghanalytics.core.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code marts.repo_commits_monthly} (R__marts_repo_commits_monthly.sql) on a real PostgreSQL 16. The
 * collection week comes from {@code core.data_snapshots}: V5 gives 2026-11-15 for data-commits-2026-11-20.
 */
@Testcontainers
class MartsRepoCommitsMonthlyIT {

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
        jdbc.update("DELETE FROM core.repos");   // weekly rows go by ON DELETE CASCADE
        jdbc.update("DELETE FROM core.data_snapshots WHERE release LIKE 'local-%'");
        jdbc.execute("SET TIME ZONE 'Europe/Moscow'");
    }

    private static void repo(long id, String createdAt) {
        jdbc.update("""
                INSERT INTO core.repos (id, node_id, full_name, owner_type, created_at, stars, forks, is_fork, is_archived)
                VALUES (?, ?, ?, 'User', ?::timestamptz, 10, 0, false, false)""",
                id, "R_" + id, "a/r" + id, createdAt);
    }

    private static void week(long repoId, String sunday, int commits) {
        jdbc.update("INSERT INTO core.repo_commits_weekly (repo_id, week, commits, contributors) VALUES (?, ?::date, ?, ?)",
                repoId, sunday, commits, commits > 0 ? 1 : 0);
    }

    private static List<Map<String, Object>> months(long repoId) {
        return jdbc.queryForList("""
                SELECT month::text AS month, commits, active_weeks, observed_weeks, stats_truncated
                FROM marts.repo_commits_monthly WHERE repo_id = ? ORDER BY month""", repoId);
    }

    /** Created on Sunday 2018-01-28 in the first window of the sample. */
    private static void sampleRepository() {
        repo(1, "2018-01-28T18:51:00Z");
        week(1, "2018-01-21", 5);   // before the week of creation: imported history
        week(1, "2018-01-28", 3);
        week(1, "2018-02-04", 0);
        week(1, "2018-02-25", 2);
        week(1, "2018-03-04", 1);
        week(1, "2026-11-08", 4);
        week(1, "2026-11-15", 9);   // the week of collection: partial
    }

    @Test
    void weeksAreSummedByTheMonthOfTheirSundayFromTheWeekOfCreation() {
        sampleRepository();

        assertThat(months(1)).extracting(m -> m.get("month") + " " + m.get("commits") + " " + m.get("active_weeks") + " "
                        + m.get("observed_weeks"))
                .containsExactly("2018-01-01 3 1 1", "2018-02-01 2 1 2", "2018-03-01 1 1 1", "2026-11-01 4 1 1");
    }

    @Test
    void theFlagOfTruncatedStatisticsIsCarried() {
        sampleRepository();
        jdbc.update("UPDATE core.repos SET stats_truncated = true WHERE id = 1");

        assertThat(months(1)).extracting(m -> m.get("stats_truncated")).containsOnly(true);
    }

    @Test
    void aLaterLocalSnapshotMovesTheCutoff() {
        sampleRepository();
        week(1, "2026-11-22", 6);
        jdbc.update("""
                INSERT INTO core.data_snapshots (release, source, collected_from, collected_to, collection_week)
                VALUES ('local-2026-11-25', 'github-commits', '2026-11-25T10:00Z', '2026-11-25T13:00Z', '2026-11-22')""");

        Map<String, Object> november = months(1).get(3);

        assertThat(november).as("2026-11-15 is complete now, 2026-11-22 is the partial week")
                .containsEntry("commits", 13L).containsEntry("observed_weeks", 2L);
    }

    @Test
    void withoutASnapshotTheLastWeekInTheTableIsTakenForTheWeekOfCollection() {
        sampleRepository();
        jdbc.update("DELETE FROM core.data_snapshots WHERE source = 'github-commits'");
        try {
            Map<String, Object> november = months(1).get(3);

            assertThat(november).containsEntry("commits", 4L).containsEntry("observed_weeks", 1L);
        } finally {
            jdbc.update("""
                    INSERT INTO core.data_snapshots (release, source, collected_from, collected_to, collection_week, note)
                    VALUES ('data-commits-2026-11-20', 'github-commits', '2026-11-19T19:13:12Z', '2026-11-19T22:18:45Z',
                            '2026-11-15', 'restored by the test')""");
        }
    }

    @Test
    void repositoriesOutsideTheSampleAreLeftOut() {
        repo(2, "2017-06-01T00:00:00Z");
        week(2, "2018-01-28", 7);

        assertThat(months(2)).isEmpty();
    }
}
