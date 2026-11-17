package ru.spbstu.ghanalytics.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.spbstu.ghanalytics.core.CollectionContext;
import ru.spbstu.ghanalytics.core.http.HttpStatusException;
import ru.spbstu.ghanalytics.core.http.RetryableHttpException;
import ru.spbstu.ghanalytics.core.run.CollectionRunService;
import ru.spbstu.ghanalytics.core.run.RunStats;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The page loop on a stubbed client: no network, no database. */
class RepoCollectorTest {

    private static final LocalDate FROM = LocalDate.of(2024, 1, 1);
    private static final LocalDate TO = LocalDate.of(2024, 1, 31);

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final GitHubGraphQlClient client = mock(GitHubGraphQlClient.class);
    private final GitHubRestClient rest = mock(GitHubRestClient.class);   // unstubbed: Optional.empty(), "no README"
    private final RepoRepository repository = mock(RepoRepository.class);
    private final CollectionRunService runs = mock(CollectionRunService.class);
    private final RunStats stats = new RunStats();
    private final TestClock clock = new TestClock(Instant.parse("2026-10-25T09:00:00Z"));

    /** What the stub client answers, call by call, and the variables it was called with. */
    private final Deque<Supplier<JsonNode>> answers = new ArrayDeque<>();
    private final List<Map<String, Object>> calls = new ArrayList<>();
    private final List<List<RepoRow>> upserted = new ArrayList<>();
    private RepoCollector collector;
    private int nextId = 1;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(runs.start(eq(RepoCollector.SOURCE), anyMap())).thenReturn(7L);
        when(client.execute(anyString(), anyMap())).thenAnswer(invocation -> {
            calls.add(new HashMap<>((Map<String, Object>) invocation.getArgument(1)));
            return answers.remove().get();
        });
        when(repository.upsertRepos(anyList())).thenAnswer(invocation -> {
            List<RepoRow> rows = invocation.getArgument(0);
            upserted.add(rows);
            return rows.size();
        });
        collector = new RepoCollector(client, rest, new RepoMapper(), repository, runs, stats, json, clock);
    }

    private static TimeWindow window(int i) {
        Instant from = Instant.parse("2023-03-01T00:00:00Z").plusSeconds(600L * i);
        return new TimeWindow(from, from.plusSeconds(599));
    }

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
    void shrinkHalvesDownToMinimum() {
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

    @Test
    void searchStringFormat() {
        assertThat(RepoCollector.searchString(new CollectionContext(FROM, TO, 10, 200, false)))
                .isEqualTo("is:public stars:>=10 created:2024-01-01..2024-01-31");
    }

    @Test
    void collectsPagesUntilTheLimitAskingOnlyForTheRest() {
        answers.add(() -> page(10, true, "c1"));
        answers.add(() -> page(5, true, "c2"));

        collector.collect(new CollectionContext(FROM, TO, 10, 15, false));

        assertThat(calls).hasSize(2);
        assertThat(calls.get(0)).containsEntry("q", "is:public stars:>=10 created:2024-01-01..2024-01-31")
                .containsEntry("first", 10).containsEntry("cursor", null);
        assertThat(calls.get(1)).containsEntry("first", 5).containsEntry("cursor", "c1");
        assertThat(upserted).extracting(List::size).containsExactly(10, 5);
        verify(runs).finish(eq(7L), eq(15L), any());
        verify(runs, never()).fail(anyLong(), anyString());
    }

    @Test
    void stopsWhenThereIsNoNextPage() {
        answers.add(() -> page(10, true, "c1"));
        answers.add(() -> page(3, false, null));

        collector.collect(new CollectionContext(FROM, TO, 10, 0, false));

        assertThat(calls).extracting(c -> c.get("first")).containsExactly(10, 10);
        verify(runs).finish(eq(7L), eq(13L), any());
    }

    @Test
    void shrinksThePageOnBadGatewayAndRepeatsTheSamePage() {
        answers.add(() -> {
            throw new RetryableHttpException(502, "SearchRepos: HTTP 502", null);
        });
        answers.add(() -> page(5, true, "c1"));
        answers.add(() -> page(5, false, null));

        collector.collect(new CollectionContext(FROM, TO, 10, 0, false));

        assertThat(calls).extracting(c -> c.get("first")).containsExactly(10, 5, 5);
        assertThat(calls).extracting(c -> c.get("cursor")).containsExactly(null, null, "c1");
        verify(runs).finish(eq(7L), eq(10L), any());
    }

    @Test
    void gatewayTimeoutAtTheMinimumSizeFailsTheRun() {
        for (int i = 0; i < 3; i++) {
            answers.add(() -> {
                throw new RetryableHttpException(504, "SearchRepos: HTTP 504", null);
            });
        }

        assertThatThrownBy(() -> collector.collect(new CollectionContext(FROM, TO, 10, 0, false)))
                .isInstanceOf(RetryableHttpException.class);

        assertThat(calls).extracting(c -> c.get("first")).containsExactly(10, 5, 3);
        verify(runs).fail(eq(7L), contains("504"));
        verify(runs, never()).finish(anyLong(), anyLong(), any());
    }

    @Test
    void otherErrorsFailTheRunAtOnce() {
        answers.add(() -> {
            throw new GitHubGraphQlException("SearchRepos: Field 'x' doesn't exist", null);
        });

        assertThatThrownBy(() -> collector.collect(new CollectionContext(FROM, TO, 10, 0, false)))
                .isInstanceOf(GitHubGraphQlException.class);

        assertThat(calls).hasSize(1);
        verify(runs).fail(eq(7L), contains("doesn't exist"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void writesFileTreesOfEveryRepositoryOfThePage() {
        answers.add(() -> page(2, false, null));

        collector.collect(new CollectionContext(FROM, TO, 10, 0, false));

        var ids = ArgumentCaptor.forClass(Collection.class);
        var entries = ArgumentCaptor.forClass(List.class);
        verify(repository).replaceTreeEntries(ids.capture(), entries.capture());
        assertThat(ids.getValue()).containsExactly(1L, 2L);
        assertThat((List<RepoTreeEntryRow>) entries.getValue()).extracting(RepoTreeEntryRow::path)
                .containsExactly("README.md", "README.md");
    }

    @Test
    void emptyNodesAndRepeatedRepositoriesAreSkipped() {
        answers.add(() -> {
            ObjectNode data = page(2, false, null);
            ArrayNode nodes = (ArrayNode) data.path("search").path("nodes");
            nodes.add(nodes.get(0).deepCopy());   // the same repository twice on one page
            nodes.addObject();                    // {}: not a repository
            return data;
        });

        collector.collect(new CollectionContext(FROM, TO, 10, 0, false));

        assertThat(upserted).extracting(List::size).containsExactly(2);
        verify(runs).finish(eq(7L), eq(2L), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fetchesTheReadmeOfEveryRepositoryAfterTheRepositoriesAreWritten() {
        when(rest.readme("owner/repo1")).thenReturn(Optional.of("# One"));
        answers.add(() -> page(2, false, null));

        collector.collect(new CollectionContext(FROM, TO, 10, 0, false));

        var readmes = ArgumentCaptor.forClass(List.class);
        var order = inOrder(repository, rest);
        order.verify(repository).upsertRepos(anyList());
        order.verify(rest).readme("owner/repo1");
        order.verify(rest).readme("owner/repo2");
        order.verify(repository).upsertReadmes(readmes.capture());
        assertThat((List<ReadmeRow>) readmes.getValue())
                .containsExactly(new ReadmeRow(1, "# One"), new ReadmeRow(2, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFailedReadmeIsSkippedAndCounted() {
        when(rest.readme("owner/repo1")).thenThrow(new RetryableHttpException(502, "HTTP 502", null));
        when(rest.readme("owner/repo2")).thenReturn(Optional.of("# Two"));
        answers.add(() -> page(2, false, null));

        collector.collect(new CollectionContext(FROM, TO, 10, 0, false));

        var readmes = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertReadmes(readmes.capture());
        assertThat((List<ReadmeRow>) readmes.getValue()).containsExactly(new ReadmeRow(2, "# Two"));
        assertThat(stats.snapshot()).containsEntry(RepoCollector.README_ERRORS, 1L);
        verify(runs).finish(eq(7L), eq(2L), any());
    }

    @Test
    void aRejectedTokenStopsTheRun() {
        when(rest.readme("owner/repo1")).thenThrow(new HttpStatusException(401, "GitHub rejected token ****0001"));
        answers.add(() -> page(2, false, null));

        assertThatThrownBy(() -> collector.collect(new CollectionContext(FROM, TO, 10, 0, false)))
                .isInstanceOf(HttpStatusException.class);
        verify(runs).fail(eq(7L), contains("rejected token"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEmptyRepositoryNeedsNoReadmeRequest() {
        answers.add(() -> {
            ObjectNode data = page(1, false, null);
            ((ObjectNode) data.path("search").path("nodes").get(0)).put("isEmpty", true);
            return data;
        });

        collector.collect(new CollectionContext(FROM, TO, 10, 0, false));

        verify(rest, never()).readme(anyString());
        var readmes = ArgumentCaptor.forClass(List.class);
        verify(repository).upsertReadmes(readmes.capture());
        assertThat((List<ReadmeRow>) readmes.getValue()).containsExactly(new ReadmeRow(1, null));
    }

    /** A search page with {@code n} minimal repository nodes. */
    private ObjectNode page(int n, boolean hasNextPage, String endCursor) {
        ObjectNode data = json.createObjectNode();
        data.putObject("rateLimit").put("cost", 1).put("remaining", 4990);
        ObjectNode search = data.putObject("search");
        search.put("repositoryCount", 1234);
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
}
