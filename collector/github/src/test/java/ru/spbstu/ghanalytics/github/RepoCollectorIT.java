package ru.spbstu.ghanalytics.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.spbstu.ghanalytics.core.CollectionContext;
import ru.spbstu.ghanalytics.core.run.CheckpointStore;
import ru.spbstu.ghanalytics.core.run.CollectionRunService;
import ru.spbstu.ghanalytics.core.run.RunStats;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The whole collector on a real PostgreSQL 16 (repository, checkpoints, run journal) with a stubbed
 * GitHub: every window always holds the same made-up repositories, created inside the window.
 * Checks what the SQL of the chapter checks after a live run: idempotent repeats and a resume
 * after a crash without duplicates.
 */
@Testcontainers
class RepoCollectorIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(TestDatabase.IMAGE);

    static JdbcTemplate jdbc;

    private static final CollectionContext MARCH = new CollectionContext(
            LocalDate.of(2023, 3, 1), LocalDate.of(2023, 3, 31), 10, 40, false);
    private static final CollectionContext MARCH_RESUME = new CollectionContext(
            MARCH.from(), MARCH.to(), MARCH.minStars(), MARCH.limit(), true);

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final List<String> searched = new ArrayList<>();
    /** The search call (1, 2, ...) that throws, as if the process was killed; 0 never. */
    private int crashAt;

    @TempDir
    Path reports;

    @BeforeAll
    static void migrate() {
        jdbc = TestDatabase.migrate(postgres);
    }

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM core.repos");   // readmes and tree entries go by ON DELETE CASCADE
        jdbc.update("DELETE FROM core.checkpoints");
        jdbc.update("DELETE FROM core.collection_runs");
    }

    @Test
    void secondIdenticalRunAddsNothing() {
        collector().collect(MARCH);
        Map<String, Integer> first = counts();
        List<Long> ids = ids();

        collector().collect(MARCH);

        assertThat(counts()).isEqualTo(first);
        assertThat(ids()).isEqualTo(ids);
        assertThat(first.get("repos")).isGreaterThanOrEqualTo(40);
        assertThat(first.get("readmes")).as("every repository checked").isEqualTo(first.get("repos"));
        assertThat(jdbc.queryForList("SELECT status FROM core.collection_runs", String.class))
                .containsExactly("success", "success");
        assertThat(jdbc.queryForObject("SELECT params ->> 'seed' FROM core.collection_runs LIMIT 1", String.class))
                .isEqualTo("42");
    }

    @Test
    void everyRepositoryIsInsideATakenWindow() throws Exception {
        collector().collect(MARCH);

        List<String> windows = Files.readAllLines(reports.resolve("sample-windows.csv"));
        StringBuilder values = new StringBuilder();
        for (String line : windows.subList(1, windows.size())) {
            String[] cells = line.split(",");
            values.append(values.isEmpty() ? "" : ",").append("('").append(cells[1]).append("'::timestamptz,'")
                    .append(cells[2]).append("'::timestamptz)");
        }
        Integer outside = jdbc.queryForObject("SELECT count(*) FROM core.repos r WHERE NOT EXISTS (SELECT 1 FROM (VALUES "
                + values + ") w(f, t) WHERE r.created_at BETWEEN w.f AND w.t)", Integer.class);

        assertThat(outside).isZero();
        assertThat(windows.stream().skip(1).mapToInt(l -> Integer.parseInt(l.split(",")[3])).sum())
                .isEqualTo(counts().get("repos"));
    }

    @Test
    void resumeAfterACrashGoesOnWithoutDuplicates() {
        collector().collect(MARCH);
        List<Long> clean = ids();
        int cleanSearches = searched.size();
        cleanUp();
        searched.clear();

        crashAt = 6;
        assertThatThrownBy(() -> collector().collect(MARCH)).hasMessageContaining("killed");
        List<String> beforeCrash = List.copyOf(searched.subList(0, 5));
        searched.clear();
        crashAt = 0;
        collector().collect(MARCH_RESUME);

        assertThat(ids()).isEqualTo(clean);
        assertThat(searched).as("windows done before the crash are not asked again").doesNotContainAnyElementsOf(beforeCrash)
                .hasSize(cleanSearches - 5);
        assertThat(jdbc.queryForList("SELECT status FROM core.collection_runs ORDER BY id", String.class))
                .containsExactly("failed", "success");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.checkpoints WHERE key LIKE 'window:%' AND value NOT LIKE 'done:%'",
                Integer.class)).isZero();
    }

    private RepoCollector collector() {
        GitHubGraphQlClient client = mock(GitHubGraphQlClient.class);
        when(client.execute(anyString(), anyMap())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            Map<String, Object> vars = invocation.getArgument(1);
            String q = (String) vars.get("q");
            ObjectNode data = json.createObjectNode();
            if (query.contains("CountRepos")) {
                data.putObject("search").put("repositoryCount", 22197);
                return data;
            }
            searched.add(q);
            if (searched.size() == crashAt) {
                throw new IllegalStateException("killed");
            }
            return windowPage(data, q.substring(q.indexOf("created:") + "created:".length()));
        });
        GitHubRestClient rest = mock(GitHubRestClient.class);
        when(rest.readme(anyString())).thenReturn(Optional.of("# made up"));
        return new RepoCollector(client, rest, new RepoMapper(), new RepoRepository(jdbc),
                new CollectionRunService(jdbc, json), new RunStats(), json, new TestClock(Instant.parse("2026-11-17T00:00:00Z")),
                new CheckpointStore(jdbc), new SampleProperties(42, reports));
    }

    /** 0-3 repositories, the same for the same window every time, created inside it. */
    private ObjectNode windowPage(ObjectNode data, String window) {
        String[] bounds = window.split("\\.\\.");
        Instant from = Instant.parse(bounds[0]);
        int n = Math.floorMod(window.hashCode(), 4);
        data.putObject("rateLimit").put("cost", 1).put("remaining", 4990);
        ObjectNode search = data.putObject("search");
        search.put("repositoryCount", n);
        search.putObject("pageInfo").put("hasNextPage", false);
        ArrayNode nodes = search.putArray("nodes");
        for (int i = 0; i < n; i++) {
            long id = from.getEpochSecond() * 10 + i;
            ObjectNode node = nodes.addObject();
            node.put("fullDatabaseId", String.valueOf(id));
            node.put("id", "R_" + id);
            node.put("nameWithOwner", "owner/r" + id);
            node.putObject("owner").put("__typename", "User");
            node.put("createdAt", from.plusSeconds(60L * i + 7).toString());
            node.put("stargazerCount", 10 + i);
            ArrayNode root = node.putObject("rootTree").putArray("entries");
            root.addObject().put("name", "README.md").put("type", "blob");
            root.addObject().put("name", i % 2 == 0 ? "tests" : "docs").put("type", "tree");
        }
        return data;
    }

    private static Map<String, Integer> counts() {
        Map<String, Integer> counts = new HashMap<>();
        for (String table : List.of("repos", "readmes", "repo_tree_entries")) {
            counts.put(table, jdbc.queryForObject("SELECT count(*) FROM core." + table, Integer.class));
        }
        counts.put("tested", jdbc.queryForObject("SELECT count(*) FROM core.repos WHERE has_tests", Integer.class));
        return counts;
    }

    private static List<Long> ids() {
        return jdbc.queryForList("SELECT id FROM core.repos ORDER BY id", Long.class);
    }
}
