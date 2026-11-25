package ru.spbstu.ghanalytics.core.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V5 (sample design and snapshot metadata) and {@code marts.repos_weighted} on a real PostgreSQL 16,
 * all migrations of collector-core applied by Flyway, no Spring context.
 */
@Testcontainers
class SampleDesignIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    /** The CSV files the sample was written to by github-repos (run 19); tests run in collector/core. */
    private static final Path DATA = Path.of("..", "..", "docs", "data");

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
        jdbc.update("DELETE FROM core.repos");
        jdbc.execute("SET TIME ZONE 'Europe/Moscow'");   // nothing here may depend on the session zone
    }

    private static List<String> csv(String name) throws IOException {
        List<String> lines = Files.readAllLines(DATA.resolve(name));
        return lines.subList(1, lines.size()).stream().filter(l -> !l.isBlank()).toList();
    }

    private static void repo(long id, String createdAt) {
        jdbc.update("""
                INSERT INTO core.repos (id, node_id, full_name, owner_type, created_at, stars, forks, is_fork, is_archived)
                VALUES (?, ?, ?, 'User', ?::timestamptz, 10, 0, false, false)""",
                id, "R_" + id, "a/r" + id, createdAt);
    }

    // ---- the tables hold the sample of the release ----

    @Test
    void thePopulationTableIsPopulationByMonthCsv() throws IOException {
        List<String> rows = jdbc.queryForList(
                "SELECT to_char(month, 'YYYY-MM-DD') || ',' || population FROM core.sample_population ORDER BY month",
                String.class);

        assertThat(rows).containsExactlyElementsOf(csv("population-by-month.csv"));
        assertThat(jdbc.queryForObject("SELECT sum(population) FROM core.sample_population", Long.class))
                .isEqualTo(1_804_176L);
    }

    @Test
    void theWindowTableIsSampleWindowsCsvInTheSameOrder() throws IOException {
        List<String> rows = jdbc.queryForList("""
                SELECT to_char(month, 'YYYY-MM-DD') || ','
                       || to_char(window_from AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') || ','
                       || to_char(window_to AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"') || ',' || repos
                FROM core.sample_windows ORDER BY taken""", String.class);

        assertThat(rows).as("the order of taking is kept in taken").containsExactlyElementsOf(csv("sample-windows.csv"));
        assertThat(jdbc.queryForObject("SELECT sum(repos) FROM core.sample_windows", Long.class)).isEqualTo(5250L);
    }

    @Test
    void overlappingWindowsAreRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO core.sample_windows (window_from, window_to, month, repos, taken)
                VALUES ('2018-01-28T18:55:00Z', '2018-01-28T19:04:59Z', '2018-01-01', 1, 100000)"""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- snapshot metadata ----

    @Test
    void theCommitSnapshotNamesItsPartialCollectionWeek() {
        Map<String, Object> commits = jdbc.queryForMap(
                "SELECT source, collection_week::text AS week FROM core.data_snapshots WHERE release = 'data-commits-2026-11-20'");

        assertThat(commits).containsEntry("source", "github-commits").containsEntry("week", "2026-11-15");
        assertThat(jdbc.queryForList("SELECT release FROM core.data_snapshots ORDER BY release", String.class))
                .containsExactly("data-commits-2026-11-20", "data-gharchive-2026-11-17", "data-repos-2026-11-19");
    }

    @Test
    void aCollectionWeekThatIsNotASundayIsRejected() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO core.data_snapshots (release, source, collected_from, collected_to, collection_week)
                VALUES ('local-test', 'github-commits', now(), now(), '2026-11-16')"""))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- marts.repos_weighted ----

    @Test
    void onlyRepositoriesInAWindowAreWeightedByTheirMonth() {
        repo(1, "2018-01-28T18:50:00Z");   // the first window of sample-windows.csv, both bounds included
        repo(2, "2018-01-28T18:59:59Z");
        repo(3, "2018-01-28T19:00:00Z");   // one second after it
        repo(4, "2017-06-01T00:00:00Z");   // outside the frame

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT repo_id, created_month::text AS month, month_n, month_population, weight "
                        + "FROM marts.repos_weighted ORDER BY repo_id");

        assertThat(rows).extracting(r -> r.get("repo_id")).containsExactly(1L, 2L);
        assertThat(rows.get(0)).containsEntry("month", "2018-01-01").containsEntry("month_n", 2L)
                .containsEntry("month_population", 19_399);
        assertThat((BigDecimal) rows.get(0).get("weight")).as("population / n of the month")
                .isEqualByComparingTo("9699.5");
    }

    @Test
    void aForkInAWindowIsNotInTheSample() {
        repo(1, "2018-01-28T18:52:00Z");
        repo(2, "2018-01-28T18:53:00Z");
        jdbc.update("UPDATE core.repos SET is_fork = true WHERE id = 2");

        Map<String, Object> only = jdbc.queryForMap("SELECT repo_id, month_n FROM marts.repos_weighted");

        assertThat(only).as("the search of the sample excludes forks").containsEntry("repo_id", 1L)
                .containsEntry("month_n", 1L);
    }

    @Test
    void theWindowOfARepositoryIsTheOneItWasCreatedIn() {
        repo(1, "2018-01-28T18:55:00Z");

        OffsetDateTime from = jdbc.queryForObject("SELECT window_from FROM marts.repos_weighted", OffsetDateTime.class);

        assertThat(from.toInstant()).isEqualTo(OffsetDateTime.parse("2018-01-28T18:50:00Z").toInstant());
    }
}
