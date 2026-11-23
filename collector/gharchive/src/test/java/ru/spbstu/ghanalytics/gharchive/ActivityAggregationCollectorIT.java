package ru.spbstu.ghanalytics.gharchive;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.spbstu.ghanalytics.core.CollectionContext;
import ru.spbstu.ghanalytics.core.run.RunStats;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole chain with the real Spring wiring: properties, SQL files from the classpath, the client,
 * TSV mapping, upsert, checkpoints, the run journal. A local {@link HttpServer} plays ClickHouse
 * and answers by the text of the SQL it receives.
 */
@SpringBootTest(classes = GhArchiveTestApplication.class)
@Testcontainers
class ActivityAggregationCollectorIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    static final List<String> queries = new CopyOnWriteArrayList<>();
    static final HttpServer clickHouse = startFakeClickHouse();

    @DynamicPropertySource
    static void clickHouseUrl(DynamicPropertyRegistry registry) {
        registry.add("collector.gharchive.clickhouse-url",
                () -> "http://localhost:" + clickHouse.getAddress().getPort() + "/");
    }

    @AfterAll
    static void stopFakeClickHouse() {
        clickHouse.stop(0);
    }

    @Autowired
    ActivityAggregationCollector collector;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RunStats stats;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM core.gh_activity_hourly");
        jdbc.update("DELETE FROM core.gh_activity_monthly");
        jdbc.update("DELETE FROM core.checkpoints");
        jdbc.update("DELETE FROM core.collection_runs");
        stats.reset();
        queries.clear();
    }

    @Test
    void oneDayLandsInHourlyTableAndJournal() {
        collector.collect(new CollectionContext(LocalDate.of(2024, 3, 6), LocalDate.of(2024, 3, 6), 0, 0, false));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.gh_activity_hourly", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT sum(events) FROM core.gh_activity_hourly WHERE event_type = 'PushEvent'",
                Long.class)).isEqualTo(120L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.gh_activity_hourly WHERE commits IS NULL", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT key FROM core.checkpoints WHERE source = 'gharchive' ORDER BY key", String.class))
                .containsExactly("hourly_activity:OTHER:2024-03-06..2024-03-06", "hourly_activity:PUSH:2024-03-06..2024-03-06");

        Map<String, Object> run = jdbc.queryForMap("SELECT status, rows, params->>'to' AS to_param, "
                + "(stats->>'clickhouse_queries')::int AS queries, (stats->>'rows_read')::bigint AS rows_read "
                + "FROM core.collection_runs WHERE source = 'gharchive'");
        assertThat(run).containsEntry("status", "success").containsEntry("rows", 4L).containsEntry("to_param", "2024-03-06")
                .containsEntry("queries", 5)                 // 2 estimates, quota, 2 queries
                .containsEntry("rows_read", 5_000L);         // the fake charges 1000 rows for every answer
        assertThat(queries).filteredOn(q -> q.contains("toStartOfHour") && !q.startsWith("EXPLAIN"))
                .allSatisfy(q -> assertThat(q).doesNotContain("{{event_class}}"));
    }

    @Test
    void secondRunAddsNoRowsAndResumeSkipsDoneTasks() {
        CollectionContext month = new CollectionContext(LocalDate.of(2018, 1, 1), LocalDate.of(2018, 1, 31), 0, 0, false);
        collector.collect(month);
        int hourly = jdbc.queryForObject("SELECT count(*) FROM core.gh_activity_hourly", Integer.class);
        int monthly = jdbc.queryForObject("SELECT count(*) FROM core.gh_activity_monthly", Integer.class);

        collector.collect(month);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.gh_activity_hourly", Integer.class)).isEqualTo(hourly);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.gh_activity_monthly", Integer.class)).isEqualTo(monthly);
        assertThat(monthly).isEqualTo(4 + 2);                   // 2 push + 2 other rows, 2 rows of new repositories
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.gh_activity_monthly "
                + "WHERE event_type = 'CreateEvent:repository' AND commits IS NULL", Integer.class)).isEqualTo(2);

        queries.clear();
        collector.collect(new CollectionContext(month.from(), month.to(), 0, 0, true));

        assertThat(queries).singleElement().asString().contains("system.quota_usage");   // nothing left to do
        assertThat(jdbc.queryForList("SELECT status FROM core.collection_runs ORDER BY id", String.class))
                .containsExactly("success", "success", "success");
    }

    /** Answers: EXPLAIN ESTIMATE, quota, and the three activity queries; push and other types differ by the predicate. */
    private static HttpServer startFakeClickHouse() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                String sql = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                queries.add(sql);
                String body = answer(sql);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("X-ClickHouse-Query-Id", "it");
                exchange.getResponseHeaders().add("X-ClickHouse-Summary", "{\"read_rows\":\"1000\"}");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String answer(String sql) {
        boolean push = sql.contains("AND event_type = 'PushEvent'");
        if (sql.startsWith("EXPLAIN ESTIMATE")) {
            return "database\ttable\tparts\trows\tmarks\ndefault\tgithub_events\t1\t1000\t1\n";
        }
        if (sql.contains("system.quota_usage")) {
            return "max_read_rows\tread_rows\n300000000000\t0\n";
        }
        if (sql.contains("toStartOfHour")) {
            return "ts_hour\tevent_type\tis_bot\tevents\tactors\tcommits\n" + (push
                    ? "2024-03-06 00:00:00\tPushEvent\t0\t100\t80\t150\n2024-03-06 00:00:00\tPushEvent\t1\t20\t2\t30\n"
                    : "2024-03-06 00:00:00\tWatchEvent\t0\t40\t39\t\\N\n2024-03-06 01:00:00\tWatchEvent\t0\t35\t30\t\\N\n");
        }
        String header = "month\tevent_type\tis_bot\tevents\tactors\trepos\tcommits\n";
        if (sql.contains("'CreateEvent:repository' AS event_type")) {
            return header + "2018-01-01\tCreateEvent:repository\t0\t900\t500\t900\t\\N\n"
                    + "2018-01-01\tCreateEvent:repository\t1\t10\t2\t10\t\\N\n";
        }
        return header + (push
                ? "2018-01-01\tPushEvent\t0\t2000\t800\t900\t3000\n2018-01-01\tPushEvent\t1\t30\t3\t20\t40\n"
                : "2018-01-01\tWatchEvent\t0\t500\t400\t300\t\\N\n2018-01-01\tForkEvent\t0\t90\t80\t70\t\\N\n");
    }
}
