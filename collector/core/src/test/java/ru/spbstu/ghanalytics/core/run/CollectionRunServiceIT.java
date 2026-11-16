package ru.spbstu.ghanalytics.core.run;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.spbstu.ghanalytics.core.CoreTestApplication;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against a real PostgreSQL 16 in Docker; Flyway applies the migrations from core. */
@SpringBootTest(classes = CoreTestApplication.class)
@Testcontainers
class CollectionRunServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    CollectionRunService runs;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM core.collection_runs");
    }

    @Test
    void migrationsCreateCollectorTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'core'", String.class);

        assertThat(tables).contains("repos", "readmes", "repo_tree_entries", "repo_commits_weekly",
                "gh_activity_hourly", "gh_activity_monthly", "collection_runs", "checkpoints");
    }

    @Test
    void startWritesRunningRowWithParams() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("from", "2024-01-01");
        params.put("limit", 200);

        long id = runs.start("worldbank", params);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT source, status, finished_at, params->>'from' AS from_param, (params->>'limit')::int AS limit_param "
                        + "FROM core.collection_runs WHERE id = ?", id);
        assertThat(row.get("source")).isEqualTo("worldbank");
        assertThat(row.get("status")).isEqualTo("running");
        assertThat(row.get("finished_at")).isNull();
        assertThat(row.get("from_param")).isEqualTo("2024-01-01");
        assertThat(row.get("limit_param")).isEqualTo(200);
    }

    @Test
    void startWithoutParamsStoresEmptyObject() {
        long id = runs.start("gharchive");

        String params = jdbc.queryForObject("SELECT params::text FROM core.collection_runs WHERE id = ?", String.class, id);
        assertThat(params).isEqualTo("{}");
    }

    @Test
    void finishMarksSuccessWithRowsAndStats() {
        long id = runs.start("github-repos");

        runs.finish(id, 5213, Map.of("graphql_requests", 1290, "retries", 7));

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, rows, finished_at IS NOT NULL AS finished, (stats->>'retries')::int AS retries "
                        + "FROM core.collection_runs WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("success");
        assertThat(row.get("rows")).isEqualTo(5213L);
        assertThat(row.get("finished")).isEqualTo(true);
        assertThat(row.get("retries")).isEqualTo(7);
    }

    @Test
    void finishStoresRunStatsSnapshot() {
        long id = runs.start("github-repos");
        RunStats stats = new RunStats();
        stats.add("retries", 4);
        stats.inc("http_5xx");

        runs.finish(id, 1, stats.snapshot());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT (stats->>'retries')::int AS retries, (stats->>'http_5xx')::int AS http_5xx "
                        + "FROM core.collection_runs WHERE id = ?", id);
        assertThat(row.get("retries")).isEqualTo(4);
        assertThat(row.get("http_5xx")).isEqualTo(1);
    }

    @Test
    void finishOfFinishedRunIsRejected() {
        long id = runs.start("github-repos");
        runs.finish(id, 10);

        assertThatThrownBy(() -> runs.finish(id, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(String.valueOf(id));
    }

    @Test
    void failMarksFailedWithErrorText() {
        long id = runs.start("gharchive");

        runs.fail(id, "ClickHouse quota exceeded (code 201)");

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, error, finished_at IS NOT NULL AS finished FROM core.collection_runs WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("failed");
        assertThat(row.get("error")).isEqualTo("ClickHouse quota exceeded (code 201)");
        assertThat(row.get("finished")).isEqualTo(true);
    }

    @Test
    void failCutsVeryLongErrors() {
        long id = runs.start("gharchive");

        runs.fail(id, "x".repeat(10_000));

        Integer length = jdbc.queryForObject(
                "SELECT length(error) FROM core.collection_runs WHERE id = ?", Integer.class, id);
        assertThat(length).isLessThanOrEqualTo(CollectionRunService.MAX_ERROR_LENGTH + 3);
    }

    @Test
    void failOfUnknownRunDoesNotThrow() {
        runs.fail(999_999L, "boom");
    }

    @Test
    void startRequiresSource() {
        assertThatThrownBy(() -> runs.start(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
