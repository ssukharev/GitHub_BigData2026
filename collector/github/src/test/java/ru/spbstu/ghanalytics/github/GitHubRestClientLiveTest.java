package ru.spbstu.ghanalytics.github;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.spbstu.ghanalytics.core.http.HttpClientFactory;
import ru.spbstu.ghanalytics.core.http.RetryPolicy;
import ru.spbstu.ghanalytics.core.run.RunStats;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The real REST API; runs only when {@code COLLECTOR_GITHUB_TOKENS} is set. Two requests of the REST bucket. */
@EnabledIfEnvironmentVariable(named = "COLLECTOR_GITHUB_TOKENS", matches = ".+")
class GitHubRestClientLiveTest {

    private final RunStats stats = new RunStats();
    private HttpClient http;
    private GitHubRestClient client;

    @BeforeEach
    void setUp() {
        List<String> tokens = List.of(System.getenv("COLLECTOR_GITHUB_TOKENS").split(","));
        var properties = new GitHubProperties(tokens,
                URI.create("https://api.github.com/graphql"), URI.create("https://api.github.com"));
        http = HttpClientFactory.create(HttpClientFactory.CONNECT_TIMEOUT);
        client = new GitHubRestClient(http, RetryPolicy.defaults(stats), properties, Clock.systemUTC(), stats);
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    @Test
    void readsAnExistingReadme() {
        assertThat(client.readme("octocat/Hello-World")).hasValueSatisfying(text -> assertThat(text).contains("Hello"));
    }

    @Test
    void missingRepositoryHasNoReadme() {
        assertThat(client.readme("octocat/this-repository-does-not-exist-42")).isEmpty();
        assertThat(stats.snapshot()).containsEntry(GitHubRestClient.REST_REQUESTS, 1L);
    }
}
