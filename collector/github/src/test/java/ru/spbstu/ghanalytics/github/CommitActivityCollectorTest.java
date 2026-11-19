package ru.spbstu.ghanalytics.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import ru.spbstu.ghanalytics.core.CollectionContext;
import ru.spbstu.ghanalytics.core.http.HttpStatusException;
import ru.spbstu.ghanalytics.core.http.RetryableHttpException;
import ru.spbstu.ghanalytics.core.run.CollectionRunService;
import ru.spbstu.ghanalytics.core.run.RunStats;
import ru.spbstu.ghanalytics.github.RepoRepository.RepoRef;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure functions of the collector on the fixture {@code stats-contributors.json} (the shortened answer
 * of {@code /stats/contributors} from the chapter: alice and bob, two weeks from 2021-01-03), and its
 * runs on a stubbed REST client, mocked repositories and checkpoints in memory: no network, no database,
 * no real sleep between rounds.
 */
class CommitActivityCollectorTest {

    private static final LocalDate JAN_3 = LocalDate.of(2021, 1, 3);
    private static final LocalDate JAN_10 = LocalDate.of(2021, 1, 10);
    private static final CollectionContext MARCH = new CollectionContext(LocalDate.of(2023, 3, 1),
            LocalDate.of(2023, 3, 31), 10, 30, false);
    /** The Link header of {@code pallets/flask/contributors?per_page=1&anon=1}, 19.11.2026. */
    private static final String FLASK_LINK = "<https://api.github.com/repositories/596892/contributors?per_page=1&anon=1&page=2>; "
            + "rel=\"next\", <https://api.github.com/repositories/596892/contributors?per_page=1&anon=1&page=864>; rel=\"last\"";
    private static final String NEXT_ONLY = "<https://api.github.com/repositories/596892/contributors?per_page=1&anon=1&page=2>; "
            + "rel=\"next\"";
    private static final CollectionContext MARCH_RESUME = new CollectionContext(LocalDate.of(2023, 3, 1),
            LocalDate.of(2023, 3, 31), 10, 30, true);

    private static final String CONTRIBUTORS_QUERY = "/contributors?per_page=1&anon=1";

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final GitHubRestClient rest = mock(GitHubRestClient.class);
    private final RepoRepository repository = mock(RepoRepository.class);
    private final CommitActivityRepository commits = mock(CommitActivityRepository.class);
    private final CollectionRunService runs = mock(CollectionRunService.class);
    private final RunStats stats = new RunStats();
    private final TestClock clock = new TestClock(Instant.parse("2026-11-19T09:00:00Z"));
    private final List<Duration> sleeps = new ArrayList<>();

    /** Writes in order: "weeks N", "contributors total truncated" and "checkpoint key = value". */
    private final List<String> events = new ArrayList<>();
    private final RepoCollectorTest.InMemoryCheckpoints checkpoints = new RepoCollectorTest.InMemoryCheckpoints(events);

    /**
     * Answers by request path, in order; the last one repeats. A RuntimeException is thrown.
     * A contributors request without answers gets one contributor and no Link.
     */
    private final Map<String, Deque<Object>> answers = new HashMap<>();
    private final List<String> asked = new ArrayList<>();
    /** How long one request takes on the test clock. */
    private Duration perRequest = Duration.ZERO;

    private CommitActivityCollector collector;

    @BeforeEach
    void setUp() {
        when(runs.start(eq(CommitActivityCollector.SOURCE), anyMap())).thenReturn(9L);
        when(rest.get(anyString(), anyString())).thenAnswer(invocation -> {
            String path = invocation.getArgument(0);
            asked.add(path);
            clock.advance(perRequest);
            Deque<Object> queue = answers.get(path);
            if (queue == null && path.endsWith(CONTRIBUTORS_QUERY)) {
                return reply(200, "[{\"login\": \"someone\", \"contributions\": 3}]");
            }
            if (queue == null || queue.isEmpty()) {
                throw new AssertionError("unexpected request " + path);
            }
            Object answer = queue.size() > 1 ? queue.remove() : queue.peek();
            if (answer instanceof RuntimeException e) {
                throw e;
            }
            return answer;
        });
        when(commits.upsertWeeks(anyList())).thenAnswer(invocation -> {
            int size = ((List<?>) invocation.getArgument(0)).size();
            events.add("weeks " + size);
            return size;
        });
        doAnswer(invocation -> events.add("contributors " + invocation.getArgument(1) + " " + invocation.getArgument(2)))
                .when(repository).updateContributorStats(anyLong(), any(), anyBoolean());
        collector = new CommitActivityCollector(rest, repository, commits, checkpoints, runs, stats, json, clock, d -> {
            sleeps.add(d);
            clock.advance(d);
        });
    }

    // ---- aggregate ----

    @Test
    void sumsCommitsAndCountsAuthorsWithCommitsByWeek() throws IOException {
        assertThat(CommitActivityCollector.aggregate(7, fixture())).containsExactly(
                new WeekRow(7, JAN_3, 5, 2),    // 3 + 2, both authors
                new WeekRow(7, JAN_10, 4, 1));  // 0 + 4, only bob
    }

    @Test
    void weeksAreSundaysInUtcInAscendingOrder() throws IOException {
        assertThat(CommitActivityCollector.aggregate(7, fixture()))
                .extracting(WeekRow::week)
                .containsExactly(JAN_3, JAN_10)
                .allSatisfy(week -> assertThat(week.getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY));
    }

    @Test
    void aWeekWithoutCommitsIsARowWithZerosNotAGap() throws IOException {
        JsonNode stats = json.readTree("""
                [{"author": {"login": "alice"}, "total": 3, "weeks": [
                    {"w": 1609632000, "a": 1, "d": 0, "c": 2},
                    {"w": 1610236800, "a": 0, "d": 0, "c": 0},
                    {"w": 1610841600, "a": 1, "d": 0, "c": 1}]}]""");

        assertThat(CommitActivityCollector.aggregate(7, stats)).containsExactly(
                new WeekRow(7, JAN_3, 2, 1),
                new WeekRow(7, JAN_10, 0, 0),
                new WeekRow(7, LocalDate.of(2021, 1, 17), 1, 1));
    }

    @Test
    void weeksOfAuthorsInAnyOrderComeOutSorted() throws IOException {
        JsonNode stats = json.readTree("""
                [{"author": null, "total": 1, "weeks": [{"w": 1610236800, "a": 0, "d": 0, "c": 1}]},
                 {"author": {"login": "bob"}, "total": 1, "weeks": [{"w": 1609632000, "a": 0, "d": 0, "c": 1}]}]""");

        assertThat(CommitActivityCollector.aggregate(7, stats)).containsExactly(
                new WeekRow(7, JAN_3, 1, 1),
                new WeekRow(7, JAN_10, 1, 1));
    }

    @Test
    void anEmptyArrayGivesNoRows() throws IOException {
        assertThat(CommitActivityCollector.aggregate(7, json.readTree("[]"))).isEmpty();
    }

    // ---- statuses of /stats/contributors ----

    @Test
    void okWritesTheWeeksThenTheDoneCheckpoint() throws IOException {
        repos(ref(1, "a/one"));
        stats("a/one", reply(200, fixtureText()));

        collector.collect(MARCH);

        assertThat(events).containsExactly("weeks 2", "contributors 1 false", "checkpoint 1 = done");
        verify(commits).upsertWeeks(List.of(new WeekRow(1, JAN_3, 5, 2), new WeekRow(1, JAN_10, 4, 1)));
        verify(runs).finish(eq(9L), eq(2L), any());
        assertThat(stats.snapshot()).containsEntry(CommitActivityCollector.DONE_REPOS, 1L);
    }

    @Test
    void noContentIsEmptyWithoutRows() {
        repos(ref(1, "a/empty"));
        stats("a/empty", reply(204, ""));
        contributors("a/empty", reply(204, ""));

        collector.collect(MARCH);

        assertThat(events).containsExactly("contributors 0 false", "checkpoint 1 = empty");
        verify(commits, never()).upsertWeeks(anyList());
        assertThat(stats.snapshot()).containsEntry(CommitActivityCollector.EMPTY_REPOS, 1L);
    }

    @Test
    void noContentOfARepositoryWithOnlyAnonymousCommitsKeepsItsContributors() {
        repos(ref(1, "acheong08/DuckDuckGo-API"));
        stats("acheong08/DuckDuckGo-API", reply(204, ""));
        contributors("acheong08/DuckDuckGo-API", reply(200, "[{\"type\": \"Anonymous\", \"contributions\": 18}]"));

        collector.collect(MARCH);

        assertThat(events).as("no rows: the weeks are unknown, not zero")
                .containsExactly("contributors 1 false", "checkpoint 1 = empty");
    }

    @Test
    void anEmptyArrayIsEmptyToo() {
        repos(ref(1, "a/none"));
        stats("a/none", reply(200, "[]"));
        contributors("a/none", reply(200, "[]"));

        collector.collect(MARCH);

        assertThat(events).containsExactly("weeks 0", "contributors 0 false", "checkpoint 1 = empty");
    }

    @Test
    void notFoundIsMissing() {
        repos(ref(1, "a/gone"));
        stats("a/gone", reply(404, "{\"message\": \"Not Found\"}"));

        collector.collect(MARCH);

        assertThat(events).containsExactly("checkpoint 1 = missing");
        assertThat(asked).containsExactly(statsPath("a/gone"));
        assertThat(stats.snapshot()).containsEntry(CommitActivityCollector.MISSING_REPOS, 1L);
    }

    // ---- 202 and rounds ----

    @Test
    void acceptedIsAskedAgainInTheNextRoundAfterTheRestOfTheMinimalRound() throws IOException {
        repos(ref(1, "a/one"));
        stats("a/one", reply(202, "{}"), reply(200, fixtureText()));

        collector.collect(MARCH);

        assertThat(asked).containsExactly(statsPath("a/one"), statsPath("a/one"), contributorsPath("a/one"));
        assertThat(sleeps).containsExactly(CommitActivityCollector.MIN_ROUND);
        assertThat(events).containsExactly("weeks 2", "contributors 1 false", "checkpoint 1 = done");
        assertThat(stats.snapshot()).containsEntry(CommitActivityCollector.ROUNDS, 2L)
                .containsEntry(CommitActivityCollector.ROUND_WAIT_S, 30L)
                .containsEntry(CommitActivityCollector.PENDING_REPOS, 0L);
    }

    @Test
    void aLongRoundIsThePauseItself() throws IOException {
        perRequest = Duration.ofSeconds(20);
        repos(ref(1, "a/one"), ref(2, "b/two"), ref(3, "c/three"));
        stats("a/one", reply(202, "{}"), reply(200, fixtureText()));
        stats("b/two", reply(200, fixtureText()));
        stats("c/three", reply(202, "{}"), reply(204, ""));

        collector.collect(MARCH);

        assertThat(sleeps).as("round 1 took 80 s").isEmpty();
        assertThat(asked).containsExactly(statsPath("a/one"), statsPath("b/two"), contributorsPath("b/two"),
                statsPath("c/three"), statsPath("a/one"), contributorsPath("a/one"), statsPath("c/three"),
                contributorsPath("c/three"));
        assertThat(checkpoints.get(CommitActivityCollector.SOURCE, "3")).contains("empty");
    }

    @Test
    void onlyTheRestOfTheMinimalRoundIsWaited() throws IOException {
        perRequest = Duration.ofSeconds(10);
        repos(ref(1, "a/one"));
        stats("a/one", reply(202, "{}"), reply(200, fixtureText()));

        collector.collect(MARCH);

        assertThat(sleeps).containsExactly(Duration.ofSeconds(20));
    }

    @Test
    void stillAcceptedAfterTheLastRoundGetsNoCheckpoint() {
        repos(ref(1, "a/slow"));
        stats("a/slow", reply(202, "{}"));

        collector.collect(MARCH);

        assertThat(asked).hasSize(CommitActivityCollector.MAX_ROUNDS);
        assertThat(sleeps).as("no pause after the last round").hasSize(CommitActivityCollector.MAX_ROUNDS - 1);
        assertThat(events).isEmpty();
        assertThat(stats.snapshot()).containsEntry(CommitActivityCollector.PENDING_REPOS, 1L);
        verify(runs).finish(eq(9L), eq(0L), any());
    }

    @Test
    void nothingPendingMeansOneRoundWithoutAPause() throws IOException {
        repos(ref(1, "a/one"), ref(2, "b/two"));
        stats("a/one", reply(200, fixtureText()));
        stats("b/two", reply(404, ""));

        collector.collect(MARCH);

        assertThat(sleeps).isEmpty();
        assertThat(stats.snapshot()).containsEntry(CommitActivityCollector.ROUNDS, 1L);
    }

    // ---- errors ----

    @Test
    void anErrorAfterTheRetriesIsSkippedWithoutACheckpoint() throws IOException {
        repos(ref(1, "a/broken"), ref(2, "b/two"));
        stats("a/broken", new RetryableHttpException(502, "HTTP 502", null));
        stats("b/two", reply(200, fixtureText()));

        collector.collect(MARCH);

        assertThat(events).containsExactly("weeks 2", "contributors 1 false", "checkpoint 2 = done");
        assertThat(stats.snapshot()).containsEntry(CommitActivityCollector.FAILED_REPOS, 1L);
        assertThat(asked).as("not asked again in the same run").containsOnlyOnce(statsPath("a/broken"));
        verify(runs).finish(eq(9L), eq(2L), any());
    }

    @Test
    void unavailableForLegalReasonsIsSkippedToo() {
        repos(ref(1, "a/dmca"));
        stats("a/dmca", new HttpStatusException(451, "HTTP 451: Repository access blocked"));

        collector.collect(MARCH);

        assertThat(events).isEmpty();
        assertThat(stats.snapshot()).containsEntry(CommitActivityCollector.FAILED_REPOS, 1L);
    }

    @Test
    void okThatIsNotAnArrayIsAnErrorNotAnEmptyRepository() {
        repos(ref(1, "a/odd"), ref(2, "b/odd"));
        stats("a/odd", reply(200, "{}"));
        stats("b/odd", reply(200, ""));

        collector.collect(MARCH);

        assertThat(events).isEmpty();
        assertThat(stats.snapshot()).containsEntry(CommitActivityCollector.FAILED_REPOS, 2L);
    }

    @Test
    void anUnexpectedStatusIsAnError() {
        repos(ref(1, "a/odd"));
        stats("a/odd", reply(201, "[]"));

        collector.collect(MARCH);

        assertThat(events).isEmpty();
        assertThat(stats.snapshot()).containsEntry(CommitActivityCollector.FAILED_REPOS, 1L);
    }

    @Test
    void aRejectedTokenFailsTheRun() {
        repos(ref(1, "a/one"), ref(2, "b/two"));
        stats("a/one", new HttpStatusException(401, "GitHub rejected token ****0001"));

        assertThatThrownBy(() -> collector.collect(MARCH)).isInstanceOf(HttpStatusException.class);

        assertThat(asked).hasSize(1);
        verify(runs).fail(eq(9L), contains("rejected token"));
        verify(runs, never()).finish(anyLong(), anyLong(), any());
    }

    @Test
    void aDatabaseErrorFailsTheRun() throws IOException {
        repos(ref(1, "a/one"));
        stats("a/one", reply(200, fixtureText()));
        doThrow(new DataAccessResourceFailureException("connection refused")).when(commits).upsertWeeks(anyList());

        assertThatThrownBy(() -> collector.collect(MARCH)).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(events).isEmpty();
        verify(runs).fail(eq(9L), contains("connection refused"));
    }

    // ---- resume, journal ----

    @Test
    void resumeSkipsRepositoriesWithACheckpointAndAsksTheOthers() throws IOException {
        checkpoints.put(CommitActivityCollector.SOURCE, "1", "done");
        checkpoints.put(CommitActivityCollector.SOURCE, "2", "missing");
        events.clear();
        repos(ref(1, "a/one"), ref(2, "b/two"), ref(3, "c/three"));
        stats("c/three", reply(200, fixtureText()));

        collector.collect(MARCH_RESUME);

        assertThat(asked).containsExactly(statsPath("c/three"), contributorsPath("c/three"));
        assertThat(stats.snapshot()).containsEntry(CommitActivityCollector.RESUMED_REPOS, 2L);
    }

    @Test
    void withoutResumeCheckpointsAreOnlyWritten() throws IOException {
        checkpoints.put(CommitActivityCollector.SOURCE, "1", "missing");
        repos(ref(1, "a/one"));
        stats("a/one", reply(200, fixtureText()));

        collector.collect(MARCH);

        assertThat(asked).containsExactly(statsPath("a/one"), contributorsPath("a/one"));
        assertThat(checkpoints.get(CommitActivityCollector.SOURCE, "1")).contains("done");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theRunHasTheContextAsParamsAndTheCountersAsStats() throws IOException {
        repos(ref(1, "a/one"));
        stats("a/one", reply(200, fixtureText()));

        collector.collect(MARCH);

        verify(runs).start(CommitActivityCollector.SOURCE, MARCH.toParams());
        verify(repository).findRefs(MARCH);
        ArgumentCaptor<Map<String, ?>> counters = ArgumentCaptor.forClass(Map.class);
        verify(runs).finish(eq(9L), eq(2L), counters.capture());
        assertThat((Map<String, Object>) counters.getValue()).containsEntry(CommitActivityCollector.PENDING_REPOS, 0L)
                .containsEntry(CommitActivityCollector.TRUNCATED_REPOS, 0L)
                .containsEntry(CommitActivityCollector.DONE_REPOS, 1L)
                .containsEntry(CommitActivityCollector.ROUNDS, 1L);
    }

    @Test
    void noRepositoriesMakeAnEmptySuccessfulRun() {
        repos();

        collector.collect(MARCH);

        assertThat(asked).isEmpty();
        verify(runs).finish(eq(9L), eq(0L), any());
    }

    // ---- truncation and contributors_total ----

    @Test
    void ninetyNineAuthorsAreAFullAnswerAndOneHundredACutOne() throws IOException {
        assertThat(CommitActivityCollector.isTruncated(json.readTree(authors(99)))).isFalse();
        assertThat(CommitActivityCollector.isTruncated(json.readTree(authors(100)))).isTrue();
        assertThat(CommitActivityCollector.isTruncated(json.readTree("[]"))).isFalse();
    }

    /** Full run 19.11.2026: NVIDIA/tensorflow 500 authors, llvm/llvm-project-release-prs 499, AU-14/ColonialMarinesUniverse 497. */
    @Test
    void anAnswerNearFiveHundredAuthorsIsCutTooButOneInBetweenIsNot() throws IOException {
        assertThat(CommitActivityCollector.isTruncated(json.readTree(authors(101)))).isFalse();
        assertThat(CommitActivityCollector.isTruncated(json.readTree(authors(440)))).as("komikku-app/komikku").isFalse();
        assertThat(CommitActivityCollector.isTruncated(json.readTree(authors(489)))).isFalse();
        assertThat(CommitActivityCollector.isTruncated(json.readTree(authors(490)))).isTrue();
        assertThat(CommitActivityCollector.isTruncated(json.readTree(authors(497)))).isTrue();
        assertThat(CommitActivityCollector.isTruncated(json.readTree(authors(500)))).isTrue();
    }

    @Test
    void lastPageIsTakenFromTheLastLinkOnly() {
        assertThat(CommitActivityCollector.lastPage(FLASK_LINK)).hasValue(864);
        assertThat(CommitActivityCollector.lastPage(NEXT_ONLY)).isEmpty();
        assertThat(CommitActivityCollector.lastPage(null)).isEmpty();
        assertThat(CommitActivityCollector.lastPage("<https://api.github.com/repositories/1/contributors?page=7&per_page=1&anon=1>; "
                + "rel=\"last\"")).as("page before per_page").hasValue(7);
    }

    @Test
    void perPageOneIsNotTakenForPageOne() {
        assertThat(CommitActivityCollector.lastPage("<https://api.github.com/repositories/1/contributors?per_page=1&anon=1>; "
                + "rel=\"last\"")).isEmpty();
        assertThat(CommitActivityCollector.lastPage("<https://api.github.com/repositories/1/contributors?per_page=1&anon=1&page=12>; "
                + "rel=\"last\"")).hasValue(12);
    }

    @Test
    void contributorsTotalIsTheLastPageOfLinkOrTheBodyLength() {
        contributors("a/flask", reply(200, "[{\"login\": \"davidism\"}]", "link", FLASK_LINK));
        contributors("b/one", reply(200, "[{\"login\": \"afwfv\"}]"));
        contributors("c/none", reply(200, "[]"));
        contributors("d/empty", reply(204, ""));
        contributors("e/gone", reply(404, "{\"message\": \"Not Found\"}"));
        contributors("f/linux", new HttpStatusException(403,
                "HTTP 403: The history or contributor list is too large to list contributors for this repository via the API."));

        assertThat(collector.contributorsTotal("a/flask")).as("not the body length 1").isEqualTo(864);
        assertThat(collector.contributorsTotal("b/one")).isEqualTo(1);
        assertThat(collector.contributorsTotal("c/none")).isZero();
        assertThat(collector.contributorsTotal("d/empty")).isZero();
        assertThat(collector.contributorsTotal("e/gone")).isNull();
        assertThat(collector.contributorsTotal("f/linux")).isNull();
    }

    @Test
    void otherErrorsOfTheContributorsRequestGoOut() {
        contributors("a/one", new HttpStatusException(451, "HTTP 451: Repository access blocked"));

        assertThatThrownBy(() -> collector.contributorsTotal("a/one")).isInstanceOf(HttpStatusException.class);
    }

    @Test
    void oneHundredAuthorsMarkTheRepositoryTruncatedAndItsRowsAreStillWritten() {
        repos(ref(1, "pallets/flask"));
        stats("pallets/flask", reply(200, authors(100)));
        contributors("pallets/flask", reply(200, "[{\"login\": \"davidism\"}]", "Link", FLASK_LINK));

        collector.collect(MARCH);

        assertThat(events).containsExactly("weeks 1", "contributors 864 true", "checkpoint 1 = done");
        verify(commits).upsertWeeks(List.of(new WeekRow(1, JAN_3, 100, 100)));
        assertThat(stats.snapshot()).containsEntry(CommitActivityCollector.TRUNCATED_REPOS, 1L);
    }

    @Test
    void contributorsAreAskedOnceAfterTheOkNotInEveryRound() throws IOException {
        repos(ref(1, "a/one"));
        stats("a/one", reply(202, "{}"), reply(202, "{}"), reply(200, fixtureText()));

        collector.collect(MARCH);

        assertThat(asked).filteredOn(path -> path.endsWith(CONTRIBUTORS_QUERY)).containsExactly(contributorsPath("a/one"));
        assertThat(asked.getLast()).isEqualTo(contributorsPath("a/one"));
    }

    @Test
    void aContributorListTooLargeLeavesTheTotalUnknownAndTheRepositoryDone() throws IOException {
        repos(ref(1, "torvalds/linux"));
        stats("torvalds/linux", reply(200, fixtureText()));
        contributors("torvalds/linux", new HttpStatusException(403, "HTTP 403: The history or contributor list is too large"));

        collector.collect(MARCH);

        assertThat(events).containsExactly("weeks 2", "contributors null false", "checkpoint 1 = done");
        assertThat(stats.snapshot()).doesNotContainKey(CommitActivityCollector.FAILED_REPOS);
    }

    @Test
    void aFailedContributorsRequestLeavesTheRepositoryForTheNextRun() throws IOException {
        repos(ref(1, "a/one"));
        stats("a/one", reply(200, fixtureText()));
        contributors("a/one", new RetryableHttpException(502, "HTTP 502", null));

        collector.collect(MARCH);

        assertThat(events).as("the weeks are written, the repository has no checkpoint").containsExactly("weeks 2");
        assertThat(stats.snapshot()).containsEntry(CommitActivityCollector.FAILED_REPOS, 1L);
    }

    // ---- helpers ----

    private JsonNode fixture() throws IOException {
        return json.readTree(fixtureText());
    }

    private String fixtureText() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/stats-contributors.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void repos(RepoRef... refs) {
        when(repository.findRefs(any())).thenReturn(List.of(refs));
    }

    private static RepoRef ref(long id, String fullName) {
        return new RepoRef(id, fullName, false);
    }

    private void stats(String fullName, Object... replies) {
        answers.put(statsPath(fullName), new ArrayDeque<>(Arrays.asList(replies)));
    }

    private static String statsPath(String fullName) {
        return "/repos/" + fullName + "/stats/contributors";
    }

    private void contributors(String fullName, Object... replies) {
        answers.put(contributorsPath(fullName), new ArrayDeque<>(Arrays.asList(replies)));
    }

    private static String contributorsPath(String fullName) {
        return "/repos/" + fullName + CONTRIBUTORS_QUERY;
    }

    /** A {@code /stats/contributors} answer of {@code n} authors with one commit each in the week of 2021-01-03. */
    private static String authors(int n) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : ",").append("{\"author\": {\"login\": \"a").append(i)
                    .append("\"}, \"total\": 1, \"weeks\": [{\"w\": 1609632000, \"a\": 1, \"d\": 0, \"c\": 1}]}");
        }
        return sb.append("]").toString();
    }

    /** A response with headers given as name, value, name, value. */
    private static HttpResponse<String> reply(int status, String body, String... headers) {
        Map<String, List<String>> map = new HashMap<>();
        for (int i = 0; i < headers.length; i += 2) {
            map.put(headers[i], List.of(headers[i + 1]));
        }
        return new StubResponse(status, body, HttpHeaders.of(map, (name, value) -> true));
    }

    private record StubResponse(int statusCode, String body, HttpHeaders headers) implements HttpResponse<String> {

        @Override
        public HttpRequest request() {
            return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public URI uri() {
            return null;
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_2;
        }
    }
}
