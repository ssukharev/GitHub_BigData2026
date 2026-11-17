package ru.spbstu.ghanalytics.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import ru.spbstu.ghanalytics.core.CollectionContext;
import ru.spbstu.ghanalytics.core.http.HttpStatusException;
import ru.spbstu.ghanalytics.core.http.RetryableHttpException;
import ru.spbstu.ghanalytics.core.run.CheckpointStore;
import ru.spbstu.ghanalytics.core.run.CollectionRunService;
import ru.spbstu.ghanalytics.core.run.RunStats;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure functions of the collector, and its runs on a stubbed GraphQL client (counts and search pages
 * made up by the test), a mocked repository and REST client and checkpoints in memory: no network, no database.
 */
class RepoCollectorTest {

    private static final LocalDate JAN_FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate JAN_TO = LocalDate.of(2024, 1, 31);
    private static final DateRange JANUARY = new DateRange(JAN_FROM, JAN_TO);
    private static final String JANUARY_Q = "is:public stars:>=10 created:2024-01-01..2024-01-31";

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final GitHubGraphQlClient client = mock(GitHubGraphQlClient.class);
    private final GitHubRestClient rest = mock(GitHubRestClient.class);   // unstubbed: Optional.empty(), "no README"
    private final RepoRepository repository = mock(RepoRepository.class);
    private final CollectionRunService runs = mock(CollectionRunService.class);
    private final RunStats stats = new RunStats();
    private final TestClock clock = new TestClock(Instant.parse("2026-10-25T09:00:00Z"));

    /** Writes in order: repos, trees, readmes, "checkpoint key = value". */
    private final List<String> events = new ArrayList<>();
    private final InMemoryCheckpoints checkpoints = new InMemoryCheckpoints(events);

    /** Count of a created: qualifier; 1000 for any range by default. */
    private Function<String, Integer> countFor = qualifier -> 1000;
    /** Repositories in a window when no page is queued: one by default. */
    private Function<String, Integer> reposFor = qualifier -> 1;
    /** Search pages to answer first, in order. */
    private final Deque<Supplier<JsonNode>> pages = new ArrayDeque<>();
    private final List<String> countCalls = new ArrayList<>();
    private final List<Map<String, Object>> searchCalls = new ArrayList<>();

    @TempDir
    Path reports;

    private RepoCollector collector;
    private int nextId = 1;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(runs.start(eq(RepoCollector.SOURCE), anyMap())).thenReturn(7L);
        when(client.execute(anyString(), anyMap())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            Map<String, Object> vars = new HashMap<>((Map<String, Object>) invocation.getArgument(1));
            String q = (String) vars.get("q");
            String qualifier = q.substring(q.indexOf("created:"));
            if (query.contains("CountRepos")) {
                countCalls.add(q);
                ObjectNode data = json.createObjectNode();
                data.putObject("search").put("repositoryCount", countFor.apply(qualifier));
                return data;
            }
            searchCalls.add(vars);
            return pages.isEmpty() ? page(reposFor.apply(qualifier), false, null) : pages.remove().get();
        });
        when(repository.upsertRepos(anyList())).thenAnswer(invocation -> {
            events.add("repos");
            return ((List<?>) invocation.getArgument(0)).size();
        });
        doAnswer(invocation -> events.add("trees")).when(repository).replaceTreeEntries(anyCollection(), anyList());
        doAnswer(invocation -> events.add("readmes")).when(repository).upsertReadmes(anyList());
        collector = collector(42);
    }

    private RepoCollector collector(long seed) {
        return new RepoCollector(client, rest, new RepoMapper(), repository, runs, stats, json, clock, checkpoints,
                new SampleProperties(seed, reports));
    }

    private static CollectionContext january(int limit, boolean resume) {
        return new CollectionContext(JAN_FROM, JAN_TO, 10, limit, resume);
    }

    private static List<TimeWindow> planOfJanuary(long seed) {
        return new SampleWindowPlanner(seed, SampleWindowPlanner.DEFAULT_WIDTH).windows(JANUARY);
    }

    private static String q(TimeWindow window) {
        return "is:public stars:>=10 " + window.toQualifier();
    }

    private static String firstWindowKey() {
        return RepoCollector.windowKey(planOfJanuary(42).getFirst().toQualifier(), 10);
    }

    private static TimeWindow window(int i) {
        Instant from = Instant.parse("2023-03-01T00:00:00Z").plusSeconds(600L * i);
        return new TimeWindow(from, from.plusSeconds(599));
    }

    // ---- pure functions ----

    @Test
    void walkStopsWhenQuotaIsReached() {
        List<TimeWindow> order = IntStream.range(0, 10).mapToObj(RepoCollectorTest::window).toList();
        int[] repos = {3, 0, 5, 4, 7, 1, 1, 1, 1, 1};
        List<TimeWindow> asked = new ArrayList<>();
        ToIntFunction<TimeWindow> fake = w -> {
            asked.add(w);
            return repos[order.indexOf(w)];
        };

        List<RepoCollector.WindowResult> taken = RepoCollector.walk(order, 8, fake);

        assertThat(taken).extracting(RepoCollector.WindowResult::repos).containsExactly(3, 0, 5);   // 3 + 0 + 5 = 8 >= 8
        assertThat(asked).containsExactlyElementsOf(order.subList(0, 3));                           // nothing asked after
    }

    @Test
    void walkTakesWholeLastWindowEvenIfItOvershoots() {
        List<RepoCollector.WindowResult> taken = RepoCollector.walk(List.of(window(0), window(1)), 3, w -> 10);

        assertThat(taken).containsExactly(new RepoCollector.WindowResult(window(0), 10));
    }

    @Test
    void walkTakesEverythingWhenQuotaIsUnreachable() {
        List<TimeWindow> order = IntStream.range(0, 4).mapToObj(RepoCollectorTest::window).toList();

        List<RepoCollector.WindowResult> taken = RepoCollector.walk(order, 100, w -> 1);

        assertThat(taken).extracting(RepoCollector.WindowResult::window).containsExactlyElementsOf(order);
    }

    @Test
    void walkWithZeroQuotaAsksNothing() {
        assertThat(RepoCollector.walk(List.of(window(0)), 0, w -> {
            throw new AssertionError("no window may be asked");
        })).isEmpty();
    }

    @Test
    void keysCarryTheParametersThatChangeTheirContent() {
        assertThat(RepoCollector.windowKey("created:2023-03-15T10:00:00Z..2023-03-15T10:09:59Z", 10))
                .isEqualTo("window:stars>=10:2023-03-15T10:00:00Z..2023-03-15T10:09:59Z");
        assertThat(RepoCollector.windowKey("created:2023-03-15..2023-03-15", 5000))
                .isEqualTo("window:stars>=5000:2023-03-15..2023-03-15");
        assertThat(RepoCollector.planKey(new CollectionContext(LocalDate.of(2018, 1, 1), LocalDate.of(2025, 12, 31),
                10, 5000, true), 42)).isEqualTo("plan:2018-01-01..2025-12-31:stars>=10:limit=5000:seed=42");
    }

    @Test
    void checkpointValuesAreParsed() {
        assertThat(RepoCollector.parseState("done:5")).isEqualTo(new RepoCollector.UnitState(5, null, true));
        assertThat(RepoCollector.parseState("13:Y3Vyc29yOnYy"))
                .isEqualTo(new RepoCollector.UnitState(13, "Y3Vyc29yOnYy", false));
        assertThatThrownBy(() -> RepoCollector.parseState("13")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> RepoCollector.parseState("done:x")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void monthKeyIsTheFirstDayOfTheMonth() {
        assertThat(RepoCollector.monthKey(new DateRange(LocalDate.of(2018, 1, 15), LocalDate.of(2018, 1, 31))))
                .isEqualTo("2018-01-01");
    }

    @Test
    void shrinkHalvesDownToMinimum() {
        assertThat(RepoCollector.shrink(20)).isEqualTo(10);
        assertThat(RepoCollector.shrink(10)).isEqualTo(5);
        assertThat(RepoCollector.shrink(5)).isEqualTo(3);
        assertThat(RepoCollector.shrink(3)).isEqualTo(3);
    }

    @Test
    void onlyGatewayTimeoutsAreTooHeavy() {
        assertThat(RepoCollector.isTooHeavy(new RetryableHttpException(502, "Bad Gateway", null))).isTrue();
        assertThat(RepoCollector.isTooHeavy(new RetryableHttpException(504, "Gateway Timeout", null))).isTrue();
        assertThat(RepoCollector.isTooHeavy(new RetryableHttpException(503, "Unavailable", null))).isFalse();
        assertThat(RepoCollector.isTooHeavy(new RetryableHttpException(429, "Too Many Requests", null))).isFalse();
        assertThat(RepoCollector.isTooHeavy(new HttpStatusException(404, "Not Found"))).isFalse();
        assertThat(RepoCollector.isTooHeavy(new IllegalStateException("502"))).isFalse();
    }

    // ---- the plan ----

    @Test
    void countsEveryMonthOnce() {
        countFor = qualifier -> qualifier.startsWith("created:2024-02") ? 900 : 1000;

        collector.collect(new CollectionContext(JAN_FROM, LocalDate.of(2024, 3, 10), 10, 3, false));

        assertThat(countCalls).containsExactly(JANUARY_Q, "is:public stars:>=10 created:2024-02-01..2024-02-29",
                "is:public stars:>=10 created:2024-03-01..2024-03-10");
        assertThat(searchCalls).as("quota 1 per month, 1 repository per window").hasSize(3);
    }

    @Test
    void takesWindowsInThePlanOrderUntilTheQuotaIsReached() {
        reposFor = qualifier -> 2;

        collector.collect(january(5, false));

        List<TimeWindow> plan = planOfJanuary(42);
        assertThat(searchCalls).extracting(c -> c.get("q")).containsExactly(q(plan.get(0)), q(plan.get(1)), q(plan.get(2)));
        assertThat(searchCalls).allSatisfy(c -> assertThat(c).containsEntry("first", RepoCollector.PAGE_SIZE));
        verify(runs).finish(eq(7L), eq(6L), any());   // 2 + 2 + 2 >= 5: the last window is taken whole
    }

    @Test
    void sameSeedAsksTheSameWindowsAndAnotherSeedOthers() {
        collector(42).collect(january(3, false));
        List<Object> first = searchCalls.stream().map(c -> c.get("q")).toList();
        searchCalls.clear();
        collector(42).collect(january(3, false));
        List<Object> again = searchCalls.stream().map(c -> c.get("q")).toList();
        searchCalls.clear();
        collector(43).collect(january(3, false));
        List<Object> other = searchCalls.stream().map(c -> c.get("q")).toList();

        assertThat(again).isEqualTo(first).hasSize(3);
        assertThat(other).isNotEqualTo(first);
    }

    @Test
    void limitZeroTakesTheMonthWholeThroughTheSlicer() {
        countFor = qualifier -> qualifier.equals("created:2024-01-01..2024-01-31") ? 1550 : 50 * days(qualifier);

        collector.collect(january(0, false));

        assertThat(countCalls).as("the month once, then its two halves").containsExactly(JANUARY_Q,
                "is:public stars:>=10 created:2024-01-01..2024-01-15", "is:public stars:>=10 created:2024-01-16..2024-01-31");
        assertThat(searchCalls).extracting(c -> c.get("q")).containsExactly(
                "is:public stars:>=10 created:2024-01-01..2024-01-15", "is:public stars:>=10 created:2024-01-16..2024-01-31");
        assertThat(checkpoints.get(RepoCollector.SOURCE, "window:stars>=10:2024-01-01..2024-01-15")).contains("done:1");
    }

    @Test
    void limitZeroRefusesALargePopulation() {
        countFor = qualifier -> RepoCollector.FULL_MODE_MAX + 1;

        assertThatThrownBy(() -> collector.collect(january(0, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("--limit");
        assertThat(searchCalls).isEmpty();
        verify(runs).fail(eq(7L), contains("--limit"));
    }

    @Test
    void aMonthUnderItsQuotaIsTakenWholeWithoutAnotherCount() {
        countFor = qualifier -> 4;
        reposFor = qualifier -> 4;

        collector.collect(january(10, false));

        assertThat(countCalls).containsExactly(JANUARY_Q);
        assertThat(searchCalls).extracting(c -> c.get("q")).containsExactly(JANUARY_Q);
        verify(runs).finish(eq(7L), eq(4L), any());
    }

    @Test
    void aMonthWithoutRepositoriesAsksNothing() {
        countFor = qualifier -> 0;

        collector.collect(january(10, false));

        assertThat(searchCalls).isEmpty();
        verify(runs).finish(eq(7L), eq(0L), any());
    }

    // ---- a window: pages, checkpoints, resume ----

    @Test
    void takesEveryPageOfAWindowAndWritesTheCheckpointAfterEachPage() {
        pages.add(() -> page(10, true, "c1"));
        pages.add(() -> page(5, false, null));

        collector.collect(january(1, false));

        String key = firstWindowKey();
        assertThat(searchCalls).extracting(c -> c.get("cursor")).containsExactly(null, "c1");
        assertThat(events).filteredOn(e -> !e.startsWith("checkpoint plan:")).containsExactly(
                "repos", "trees", "readmes", "checkpoint " + key + " = 10:c1",
                "repos", "trees", "readmes", "checkpoint " + key + " = done:15");
        verify(runs).finish(eq(7L), eq(15L), any());
    }

    @Test
    void resumeTakesDoneWindowsFromCheckpointsWithoutRequests() {
        reposFor = qualifier -> 2;
        collector.collect(january(4, false));
        assertThat(searchCalls).hasSize(2);
        searchCalls.clear();
        countCalls.clear();
        stats.reset();

        collector.collect(january(4, true));

        assertThat(searchCalls).isEmpty();
        assertThat(stats.snapshot()).containsEntry(RepoCollector.WINDOWS_RESUMED, 2L)
                .doesNotContainKey(RepoCollector.WINDOWS_FETCHED);
        verify(runs, times(2)).finish(eq(7L), eq(4L), any());
    }

    @Test
    void resumeGoesOnFromTheSavedCursor() {
        checkpoints.put(RepoCollector.SOURCE, firstWindowKey(), "10:c1");
        pages.add(() -> page(5, false, null));

        collector.collect(january(1, true));

        assertThat(searchCalls).extracting(c -> c.get("cursor")).containsExactly("c1");
        assertThat(checkpoints.get(RepoCollector.SOURCE, firstWindowKey())).contains("done:15");
        verify(runs).finish(eq(7L), eq(15L), any());
    }

    @Test
    void withoutResumeCheckpointsAreOnlyWritten() {
        checkpoints.put(RepoCollector.SOURCE, firstWindowKey(), "done:5");

        collector.collect(january(1, false));

        assertThat(searchCalls).hasSize(1);
        assertThat(checkpoints.get(RepoCollector.SOURCE, firstWindowKey())).contains("done:1");
    }

    @Test
    void aSavedCursorThatFailsStartsTheWindowOver() {
        checkpoints.put(RepoCollector.SOURCE, firstWindowKey(), "10:expired");
        pages.add(() -> {
            throw new GitHubGraphQlException("SearchRepos: invalid cursor", null);
        });
        pages.add(() -> page(3, false, null));

        collector.collect(january(1, true));

        assertThat(searchCalls).extracting(c -> c.get("cursor")).containsExactly("expired", null);
        assertThat(checkpoints.get(RepoCollector.SOURCE, firstWindowKey())).contains("done:3");
    }

    @Test
    void shrinksThePageOnBadGatewayAndRepeatsTheSamePage() {
        pages.add(() -> {
            throw new RetryableHttpException(502, "SearchRepos: HTTP 502", null);
        });
        pages.add(() -> page(5, true, "c1"));
        pages.add(() -> page(5, false, null));

        collector.collect(january(1, false));

        assertThat(searchCalls).extracting(c -> c.get("first")).containsExactly(20, 10, 10);
        assertThat(searchCalls).extracting(c -> c.get("cursor")).containsExactly(null, null, "c1");
        assertThat(stats.snapshot()).containsEntry(RepoCollector.PAGE_SHRINKS, 1L);
        verify(runs).finish(eq(7L), eq(10L), any());
    }

    @Test
    void gatewayTimeoutAtTheMinimumSizeFailsTheRun() {
        for (int i = 0; i < 4; i++) {
            pages.add(() -> {
                throw new RetryableHttpException(504, "SearchRepos: HTTP 504", null);
            });
        }

        assertThatThrownBy(() -> collector.collect(january(1, false))).isInstanceOf(RetryableHttpException.class);

        assertThat(searchCalls).extracting(c -> c.get("first")).containsExactly(20, 10, 5, 3);
        assertThat(events).noneMatch(e -> e.startsWith("checkpoint window:"));
        verify(runs).fail(eq(7L), contains("504"));
        verify(runs, never()).finish(anyLong(), anyLong(), any());
    }

    @Test
    void otherErrorsFailTheRunAtOnce() {
        pages.add(() -> {
            throw new GitHubGraphQlException("SearchRepos: Field 'x' doesn't exist", null);
        });

        assertThatThrownBy(() -> collector.collect(january(1, false))).isInstanceOf(GitHubGraphQlException.class);

        assertThat(searchCalls).hasSize(1);
        verify(runs).fail(eq(7L), contains("doesn't exist"));
    }

    // ---- a page: trees, features, READMEs ----

    @Test
    @SuppressWarnings("unchecked")
    void writesFileTreesOfEveryRepositoryOfThePage() {
        pages.add(() -> page(2, false, null));

        collector.collect(january(1, false));

        var ids = ArgumentCaptor.forClass(Collection.class);
        var entries = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceTreeEntries(ids.capture(), entries.capture());
        assertThat(ids.getValue()).containsExactly(1L, 2L);
        assertThat((List<RepoTreeEntryRow>) entries.getValue()).extracting(RepoTreeEntryRow::path)
                .containsExactly("README.md", "README.md");
    }

    @Test
    @SuppressWarnings("unchecked")
    void fillsFeaturesFromTheTrees() {
        pages.add(() -> page(1, false, null));

        collector.collect(january(1, false));

        var rows = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertRepos(rows.capture());
        assertThat((List<RepoRow>) rows.getValue()).singleElement()
                .extracting(RepoRow::hasTests, RepoRow::hasCi, RepoRow::hasDocker).containsExactly(false, false, false);
    }

    @Test
    void emptyNodesAndRepeatedRepositoriesAreSkipped() {
        pages.add(() -> {
            ObjectNode data = page(2, false, null);
            ArrayNode nodes = (ArrayNode) data.path("search").path("nodes");
            nodes.add(nodes.get(0).deepCopy());   // the same repository twice on one page
            nodes.addObject();                    // {}: not a repository
            nodes.addNull();                      // an inaccessible repository
            return data;
        });

        collector.collect(january(1, false));

        verify(runs).finish(eq(7L), eq(2L), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fetchesTheReadmeOfEveryRepositoryAfterTheRepositoriesAreWritten() {
        when(rest.readme("owner/repo1")).thenAnswer(invocation -> {
            events.add("readme owner/repo1");
            return Optional.of("# One");
        });
        pages.add(() -> page(2, false, null));

        collector.collect(january(1, false));

        assertThat(events).containsSubsequence("repos", "trees", "readme owner/repo1", "readmes");
        var readmes = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertReadmes(readmes.capture());
        assertThat((List<ReadmeRow>) readmes.getValue()).containsExactly(new ReadmeRow(1, "# One"), new ReadmeRow(2, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedReadmeIsSkippedAndCounted() {
        when(rest.readme("owner/repo1")).thenThrow(new RetryableHttpException(502, "HTTP 502", null));
        when(rest.readme("owner/repo2")).thenReturn(Optional.of("# Two"));
        pages.add(() -> page(2, false, null));

        collector.collect(january(1, false));

        var readmes = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertReadmes(readmes.capture());
        assertThat((List<ReadmeRow>) readmes.getValue()).containsExactly(new ReadmeRow(2, "# Two"));
        assertThat(stats.snapshot()).containsEntry(RepoCollector.README_ERRORS, 1L);
        verify(runs).finish(eq(7L), eq(2L), any());
    }

    @Test
    void aRejectedTokenStopsTheRun() {
        when(rest.readme("owner/repo1")).thenThrow(new HttpStatusException(401, "GitHub rejected token ****0001"));
        pages.add(() -> page(2, false, null));

        assertThatThrownBy(() -> collector.collect(january(1, false))).isInstanceOf(HttpStatusException.class);
        verify(runs).fail(eq(7L), contains("rejected token"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEmptyRepositoryNeedsNoReadmeRequest() {
        pages.add(() -> {
            ObjectNode data = page(1, false, null);
            ((ObjectNode) data.path("search").path("nodes").get(0)).put("isEmpty", true);
            return data;
        });

        collector.collect(january(1, false));

        verify(rest, never()).readme(anyString());
        var readmes = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertReadmes(readmes.capture());
        assertThat((List<ReadmeRow>) readmes.getValue()).containsExactly(new ReadmeRow(1, null));
    }

    // ---- helpers ----

    /** {@code created:2024-01-01..2024-01-15} gives 15. */
    private static int days(String qualifier) {
        String[] bounds = qualifier.substring("created:".length()).split("\\.\\.");
        return (int) new DateRange(LocalDate.parse(bounds[0]), LocalDate.parse(bounds[1])).days();
    }

    /** A search page with {@code n} minimal repository nodes. */
    private ObjectNode page(int n, boolean hasNextPage, String endCursor) {
        ObjectNode data = json.createObjectNode();
        data.putObject("rateLimit").put("cost", 1).put("remaining", 4990);
        ObjectNode search = data.putObject("search");
        search.put("repositoryCount", n);
        search.putObject("pageInfo").put("hasNextPage", hasNextPage).put("endCursor", endCursor);
        ArrayNode nodes = search.putArray("nodes");
        for (int i = 0; i < n; i++) {
            int id = nextId++;
            ObjectNode node = nodes.addObject();
            node.put("fullDatabaseId", String.valueOf(id));
            node.put("id", "R_" + id);
            node.put("nameWithOwner", "owner/repo" + id);
            node.putObject("owner").put("__typename", "User");
            node.put("createdAt", "2024-01-10T00:00:00Z");
            node.put("stargazerCount", 10 + id);
            node.putObject("rootTree").putArray("entries").addObject().put("name", "README.md").put("type", "blob");
        }
        return data;
    }

    /** {@link CheckpointStore} over a map; every put is also an event. */
    static final class InMemoryCheckpoints extends CheckpointStore {

        private final Map<String, String> values = new LinkedHashMap<>();
        private final List<String> events;

        InMemoryCheckpoints(List<String> events) {
            super(null);
            this.events = events;
        }

        @Override
        public Optional<String> get(String source, String key) {
            return Optional.ofNullable(values.get(source + " " + key));
        }

        @Override
        public void put(String source, String key, String value) {
            values.put(source + " " + key, value);
            events.add("checkpoint " + key + " = " + value);
        }
    }
}
