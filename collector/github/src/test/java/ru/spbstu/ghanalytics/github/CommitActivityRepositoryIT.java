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
}
