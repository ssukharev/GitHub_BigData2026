package ru.spbstu.ghanalytics.github;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Talks to the real GitHub API; runs only when {@code COLLECTOR_GITHUB_TOKENS} is set,
 * so CI without a token skips it silently. Costs 1-2 points.
 */
@EnabledIfEnvironmentVariable(named = "COLLECTOR_GITHUB_TOKENS", matches = ".+")
class GitHubGraphQlClientLiveTest {

    private final List<String> savedSources = new ArrayList<>();
    private final RunStats stats = new RunStats();
    private HttpClient http;
    private GitHubGraphQlClient client;

    @BeforeEach
    void setUp() {
        List<String> tokens = List.of(System.getenv("COLLECTOR_GITHUB_TOKENS").split(","));
        var properties = new GitHubProperties(tokens,
                URI.create("https://api.github.com/graphql"), URI.create("https://api.github.com"));
        var guard = new RateLimitGuard(new TokenPool(tokens, Clock.systemUTC()), 50, Clock.systemUTC(), Sleeper.SYSTEM, stats);
        http = HttpClientFactory.create(HttpClientFactory.CONNECT_TIMEOUT);
        client = new GitHubGraphQlClient(http, RetryPolicy.defaults(stats), guard,
                (source, key, payload) -> savedSources.add(source),
                JsonMapper.builder().findAndAddModules().build(), properties, stats);
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    @Test
    void returnsDataAndSavesRawResponse() {
        JsonNode data = client.execute("query Ping { viewer { login } rateLimit { limit cost remaining resetAt } }", Map.of());

        assertThat(data.path("viewer").path("login").asText()).isNotBlank();
        assertThat(data.path("rateLimit").path("limit").asInt()).isPositive();
        assertThat(savedSources).containsExactly("Ping");
        assertThat(stats.snapshot()).containsEntry("graphql_requests", 1L).containsEntry("graphql_points", 1L);
    }

    @Test
    void throwsOnGraphQlError() {
        assertThatThrownBy(() -> client.execute("query Broken { viewer { loginz } }", Map.of()))
                .isInstanceOf(GitHubGraphQlException.class)
                .hasMessageContaining("loginz");
    }
}
