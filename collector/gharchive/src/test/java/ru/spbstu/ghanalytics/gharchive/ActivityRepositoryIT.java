package ru.spbstu.ghanalytics.gharchive;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.spbstu.ghanalytics.gharchive.dto.ActivityRow;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** A real PostgreSQL 16 in Docker, the migrations of collector-core applied by Flyway; no Spring context. */
@Testcontainers
class ActivityRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static ActivityRepository repository;

    private static final Instant HOUR = Instant.parse("2024-03-06T15:00:00Z");
    private static final LocalDate MONTH = LocalDate.of(2018, 1, 1);

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        repository = new ActivityRepository(new NamedParameterJdbcTemplate(dataSource));
    }

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM core.gh_activity_hourly");
        jdbc.update("DELETE FROM core.gh_activity_monthly");
    }

    private static ActivityRow.Hourly hourly(String type, boolean bot, long events, Long commits) {
        return new ActivityRow.Hourly(HOUR, type, bot, events, events / 2, commits);
    }

    @Test
    void sameHourTwiceGivesOneRowWithLastValues() {
        repository.upsertHourly(List.of(hourly("PushEvent", false, 100, 150L)));
        repository.upsertHourly(List.of(hourly("PushEvent", false, 120, 170L)));

        Map<String, Object> row = jdbc.queryForMap("SELECT count(*) AS n, max(events) AS events, max(actors) AS actors, "
                + "max(commits) AS commits FROM core.gh_activity_hourly");
        assertThat(row).containsEntry("n", 1L).containsEntry("events", 120L).containsEntry("actors", 60L)
                .containsEntry("commits", 170L);
    }

    @Test
    void peopleAndBotsOfSameHourAndTypeAreTwoRows() {
        int written = repository.upsertHourly(List.of(hourly("PushEvent", false, 100, 150L), hourly("PushEvent", true, 30, 40L)));

        assertThat(written).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.gh_activity_hourly", Integer.class)).isEqualTo(2);
    }

    @Test
    void nullCommitsAreStoredAsNullAndOverwrittenBothWays() {
        repository.upsertHourly(List.of(hourly("PushEvent", false, 100, null)));
        assertThat(commits()).isNull();

        repository.upsertHourly(List.of(hourly("PushEvent", false, 100, 150L)));
        assertThat(commits()).isEqualTo(150L);

        repository.upsertHourly(List.of(hourly("PushEvent", false, 100, null)));
        assertThat(commits()).isNull();
    }

    @Test
    void hourIsStoredAsUtcInstant() {
        repository.upsertHourly(List.of(hourly("WatchEvent", false, 9, null)));

        String text = jdbc.queryForObject(
                "SELECT to_char(ts_hour AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI') FROM core.gh_activity_hourly", String.class);
        assertThat(text).isEqualTo("2024-03-06 15:00");
    }

    @Test
    void emptyListDoesNothing() {
        assertThat(repository.upsertHourly(List.of())).isZero();
        assertThat(repository.upsertMonthly(List.of())).isZero();
    }

    @Test
    void moreRowsThanOneBatch() {
        List<ActivityRow.Hourly> rows = IntStream.range(0, ActivityRepository.BATCH_SIZE + 7)
                .mapToObj(i -> new ActivityRow.Hourly(HOUR.plusSeconds(3600L * i), "PushEvent", false, i, i, (long) i))
                .toList();

        assertThat(repository.upsertHourly(rows)).isEqualTo(ActivityRepository.BATCH_SIZE + 7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.gh_activity_hourly", Integer.class))
                .isEqualTo(ActivityRepository.BATCH_SIZE + 7);
    }

    @Test
    void monthlyUpsertOverwritesAllColumns() {
        repository.upsertMonthly(List.of(new ActivityRow.Monthly(MONTH, "PushEvent", false, 10, 5, 7, 20L),
                new ActivityRow.Monthly(MONTH, "CreateEvent:repository", false, 3, 2, 3, null)));
        repository.upsertMonthly(List.of(new ActivityRow.Monthly(MONTH, "PushEvent", false, 11, 6, 8, null)));

        Map<String, Object> push = jdbc.queryForMap(
                "SELECT events, actors, repos, commits FROM core.gh_activity_monthly WHERE event_type = 'PushEvent'");
        assertThat(push).containsEntry("events", 11L).containsEntry("actors", 6L).containsEntry("repos", 8L)
                .containsEntry("commits", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.gh_activity_monthly", Integer.class)).isEqualTo(2);
    }

    private static Long commits() {
        return jdbc.queryForObject("SELECT commits FROM core.gh_activity_hourly", Long.class);
    }
}
