package ru.spbstu.ghanalytics.github;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link CommitActivityRepository} on a real PostgreSQL 16 with the migrations of collector-core, no Spring context. */
@Testcontainers
class CommitActivityRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(TestDatabase.IMAGE);

    static JdbcTemplate jdbc;

    private static final LocalDate JAN_3 = LocalDate.of(2021, 1, 3);
    private static final LocalDate JAN_10 = LocalDate.of(2021, 1, 10);

    CommitActivityRepository repository;

    @BeforeAll
    static void migrate() {
        jdbc = TestDatabase.migrate(postgres);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM core.repos");   // weekly rows go by ON DELETE CASCADE
        jdbc.update("""
                INSERT INTO core.repos (id, node_id, full_name, owner_type, created_at, stars, forks, is_fork, is_archived)
                VALUES (1, 'R_1', 'a/one', 'User', '2020-12-01T00:00:00Z', 10, 0, false, false)""");
        repository = new CommitActivityRepository(jdbc);
    }

    @Test
    void writesRowsAndReadsThemBackWithTheirDates() {
        int written = repository.upsertWeeks(List.of(new WeekRow(1, JAN_3, 5, 2), new WeekRow(1, JAN_10, 0, 0)));

        assertThat(written).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT week, commits, contributors FROM core.repo_commits_weekly ORDER BY week"))
                .containsExactly(
                        Map.of("week", Date.valueOf(JAN_3), "commits", 5, "contributors", 2),
                        Map.of("week", Date.valueOf(JAN_10), "commits", 0, "contributors", 0));
        assertThat(jdbc.queryForList("SELECT DISTINCT extract(isodow FROM week)::int FROM core.repo_commits_weekly",
                Integer.class)).as("Sunday").containsExactly(7);
    }

    @Test
    void writingTheSameWeekTwiceKeepsOneRow() {
        repository.upsertWeeks(List.of(new WeekRow(1, JAN_3, 5, 2)));

        repository.upsertWeeks(List.of(new WeekRow(1, JAN_3, 6, 3)));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.repo_commits_weekly", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT commits, contributors FROM core.repo_commits_weekly"))
                .containsEntry("commits", 6).containsEntry("contributors", 3);
    }

    @Test
    void nullContributorsDoNotEraseTheKnownNumber() {
        repository.upsertWeeks(List.of(new WeekRow(1, JAN_3, 5, 3)));

        repository.upsertWeeks(List.of(new WeekRow(1, JAN_3, 7, null)));

        assertThat(jdbc.queryForMap("SELECT commits, contributors FROM core.repo_commits_weekly"))
                .as("commits is fresh, contributors stays known").containsEntry("commits", 7).containsEntry("contributors", 3);
    }

    @Test
    void nullContributorsAreWrittenAsNullForANewWeek() {
        repository.upsertWeeks(List.of(new WeekRow(1, JAN_3, 5, null)));

        assertThat(jdbc.queryForObject("SELECT contributors FROM core.repo_commits_weekly", Integer.class)).isNull();
    }

    @Test
    void anEmptyListWritesNothing() {
        assertThat(repository.upsertWeeks(List.of())).isZero();
    }

    @Test
    void aWeekOfAnUnknownRepositoryIsRejected() {
        assertThatThrownBy(() -> repository.upsertWeeks(List.of(new WeekRow(2, JAN_3, 1, 1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---- replaceWeeks: the complete snapshot of one repository ----

    /** The review of #52: after a force-push GitHub no longer has Jan 3, so the table must not have it either. */
    @Test
    void aNewSnapshotRemovesTheWeeksItNoLongerHas() {
        repository.upsertWeeks(List.of(new WeekRow(1, JAN_3, 5, 2), new WeekRow(1, JAN_10, 4, 1)));

        int written = repository.replaceWeeks(1, List.of(new WeekRow(1, JAN_10, 7, 3)));

        assertThat(written).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT week, commits, contributors FROM core.repo_commits_weekly ORDER BY week"))
                .containsExactly(Map.of("week", Date.valueOf(JAN_10), "commits", 7, "contributors", 3));
    }

    @Test
    void anEmptySnapshotClearsTheRepositoryAndOnlyIt() {
        addRepository(2);
        repository.upsertWeeks(List.of(new WeekRow(1, JAN_3, 5, 2), new WeekRow(2, JAN_3, 1, 1)));

        assertThat(repository.replaceWeeks(1, List.of())).isZero();

        assertThat(jdbc.queryForList("SELECT repo_id FROM core.repo_commits_weekly", Long.class)).containsExactly(2L);
    }

    @Test
    void aSnapshotKeepsTheKnownContributorsOfARetainedWeek() {
        repository.upsertWeeks(List.of(new WeekRow(1, JAN_3, 5, 3)));

        repository.replaceWeeks(1, List.of(new WeekRow(1, JAN_3, 7, null), new WeekRow(1, JAN_10, 2, null)));

        assertThat(jdbc.queryForList("SELECT commits, contributors FROM core.repo_commits_weekly ORDER BY week"))
                .containsExactly(mapOf("commits", 7, "contributors", 3), mapOf("commits", 2, "contributors", null));
    }

    @Test
    void aFailedSnapshotLeavesTheOldOneWhole() {
        repository.upsertWeeks(List.of(new WeekRow(1, JAN_3, 5, 2), new WeekRow(1, JAN_10, 4, 1)));

        assertThatThrownBy(() -> repository.replaceWeeks(1, List.of(new WeekRow(1, JAN_10, -1, 0))))
                .as("commits >= 0 rejects the row").isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForList("SELECT week FROM core.repo_commits_weekly ORDER BY week", Date.class))
                .as("one statement: the delete is rolled back with the insert")
                .containsExactly(Date.valueOf(JAN_3), Date.valueOf(JAN_10));
    }

    @Test
    void aSnapshotOfAnotherRepositoryIsRejected() {
        assertThatThrownBy(() -> repository.replaceWeeks(1, List.of(new WeekRow(2, JAN_3, 1, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void addRepository(long id) {
        jdbc.update("""
                INSERT INTO core.repos (id, node_id, full_name, owner_type, created_at, stars, forks, is_fork, is_archived)
                VALUES (?, ?, ?, 'User', '2020-12-01T00:00:00Z', 10, 0, false, false)""", id, "R_" + id, "a/r" + id);
    }

    /** {@link Map#of} takes no null values. */
    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(k1, v1);
        map.put(k2, v2);
        return map;
    }
}
