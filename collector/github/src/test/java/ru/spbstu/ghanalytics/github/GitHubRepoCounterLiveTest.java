package ru.spbstu.ghanalytics.github;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.spbstu.ghanalytics.core.http.HttpClientFactory;
import ru.spbstu.ghanalytics.core.http.RetryPolicy;
import ru.spbstu.ghanalytics.core.http.Sleeper;
import ru.spbstu.ghanalytics.core.run.RunStats;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Counts on the real search; runs only when {@code COLLECTOR_GITHUB_TOKENS} is set. 6 points.
 * The live index moves (stars cross the threshold), so sums are compared with a small tolerance.
 */
@EnabledIfEnvironmentVariable(named = "COLLECTOR_GITHUB_TOKENS", matches = ".+")
class GitHubRepoCounterLiveTest {

    private static final int TOLERANCE = 5;

    private final List<String> savedSources = new ArrayList<>();
    private final RunStats stats = new RunStats();
    private HttpClient http;
    private GitHubRepoCounter counter;

    @BeforeEach
    void setUp() {
        List<String> tokens = List.of(System.getenv("COLLECTOR_GITHUB_TOKENS").split(","));
        var properties = new GitHubProperties(tokens,
                URI.create("https://api.github.com/graphql"), URI.create("https://api.github.com"));
        var guard = new RateLimitGuard(new TokenPool(tokens, Clock.systemUTC()), 50, Clock.systemUTC(), Sleeper.SYSTEM, stats);
        http = HttpClientFactory.create(HttpClientFactory.CONNECT_TIMEOUT);
        var client = new GitHubGraphQlClient(http, RetryPolicy.defaults(stats), guard,
                (source, key, payload) -> savedSources.add(source),
                JsonMapper.builder().findAndAddModules().build(), properties, stats);
        counter = new GitHubRepoCounter(client, 10);
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    @Test
    void halvesOfAMonthAddUpToTheMonth() {
        DateRange march = new DateRange(LocalDate.of(2023, 3, 1), LocalDate.of(2023, 3, 31));
        List<DateRange> halves = march.split();

        int month = counter.count(march);
        int sum = counter.count(halves.get(0)) + counter.count(halves.get(1));

        assertThat(month).as("far above the 1000 search can return").isGreaterThan(SearchSlicer.MAX_RESULTS);
        assertThat(sum).as("both boundary days are included").isCloseTo(month, within(TOLERANCE));
        assertThat(savedSources).containsOnly("CountRepos");
    }

    @Test
    void adjacentTimeWindowsAddUpToTheHour() {
        int first = counter.count("created:2023-03-15T10:00:00Z..2023-03-15T10:29:59Z");
        int second = counter.count("created:2023-03-15T10:30:00Z..2023-03-15T10:59:59Z");
        int hour = counter.count("created:2023-03-15T10:00:00Z..2023-03-15T10:59:59Z");

        assertThat(first + second).as("windows [A, A + width - 1 s] tile without gaps").isCloseTo(hour, within(2));
        assertThat(hour).isPositive();
    }
}
