package ru.spbstu.ghanalytics.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.spbstu.ghanalytics.core.http.HttpClientFactory;
import ru.spbstu.ghanalytics.core.http.RetryPolicy;
import ru.spbstu.ghanalytics.core.http.RetryableHttpException;
import ru.spbstu.ghanalytics.core.run.RunStats;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Status handling of the client against a JDK {@link HttpServer} on a free local port.
 * The guard's sleeper moves a fake clock, the policy's pauses are recorded: nothing really sleeps.
 */
class GitHubGraphQlClientTest {

    private static final String TOKEN = "ghp_testtokentesttokentesttoken0001";
    private static final Instant START = Instant.parse("2026-10-24T10:00:00Z");
    private static final String PING = "query Ping { viewer { login } rateLimit { limit cost remaining resetAt } }";
    private static final String OK_BODY = """
            {"data": {"viewer": {"login": "octocat"}, "rateLimit": {"limit": 5000, "cost": 1, "remaining": 4999}}}""";

    private record Reply(int status, Map<String, String> headers, String body) {
    }

    private record Saved(String source, String requestKey, String payload) {
    }

    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<String> authHeaders = new ArrayList<>();
    private final List<String> requestBodies = new ArrayList<>();
    private final List<Saved> saved = new ArrayList<>();
    private final TestClock clock = new TestClock(START);
    private final List<Duration> guardSleeps = new ArrayList<>();
    private final List<Duration> retrySleeps = new ArrayList<>();
    private final RunStats stats = new RunStats();
    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();
    private final HttpClient http = HttpClientFactory.create(Duration.ofSeconds(2));

    private HttpServer server;
    private TokenPool pool;
    private GitHubGraphQlClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/graphql", exchange -> {
            authHeaders.add(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Reply reply = replies.remove();
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();

        URI base = URI.create("http://localhost:" + server.getAddress().getPort());
        GitHubProperties properties = new GitHubProperties(List.of(TOKEN), base.resolve("/graphql"), base);
        pool = new TokenPool(properties.tokens(), clock);
        RateLimitGuard guard = new RateLimitGuard(pool, 50, clock, d -> {
            guardSleeps.add(d);
            clock.advance(d);
        }, stats);
        RetryPolicy retry = new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(30), retrySleeps::add, () -> 0.0, stats);
        client = new GitHubGraphQlClient(http, retry, guard,
                (source, key, payload) -> saved.add(new Saved(source, key, payload)), json, properties, stats);
    }

    @AfterEach
    void tearDown() {
        http.close();
        server.stop(0);
    }

    @Test
    void returnsDataSavesRawResponseAndCounts() throws Exception {
        replies.add(new Reply(200, limitHeaders(4999, Duration.ofMinutes(30)), OK_BODY));
        Map<String, Object> variables = new HashMap<>();
        variables.put("q", "stars:>=10");
        variables.put("first", 10);
        variables.put("cursor", null);

        JsonNode data = client.execute(PING, variables);

        assertThat(data.path("viewer").path("login").asText()).isEqualTo("octocat");
        assertThat(saved).containsExactly(new Saved("Ping", "{\"cursor\":null,\"first\":10,\"q\":\"stars:>=10\"}", OK_BODY));
        assertThat(authHeaders).containsExactly("Bearer " + TOKEN);
        JsonNode sent = json.readTree(requestBodies.get(0));
        assertThat(sent.path("query").asText()).isEqualTo(PING);
        assertThat(sent.path("variables").has("cursor")).isTrue();
        assertThat(sent.path("variables").path("cursor").isNull()).isTrue();
        assertThat(stats.snapshot()).containsEntry(GitHubGraphQlClient.GRAPHQL_REQUESTS, 1L)
                .containsEntry(GitHubGraphQlClient.GRAPHQL_POINTS, 1L)
                .doesNotContainKey(RetryPolicy.RETRIES);
        assertThat(pool.remaining(TOKEN)).as("headers went to the pool").isEqualTo(4999);
        assertThat(guardSleeps).isEmpty();
    }

    @Test
    void graphQlErrorIsNotRetried() {
        replies.add(new Reply(200, limitHeaders(4998, Duration.ofMinutes(30)), """
                {"errors": [{"path": ["query", "viewer", "loginz"], "extensions": {"code": "undefinedField"},
                             "message": "Field 'loginz' doesn't exist on type 'User'"}]}"""));

        assertThatThrownBy(() -> client.execute("query Broken { viewer { loginz } }", Map.of()))
                .isInstanceOfSatisfying(GitHubGraphQlException.class, e -> assertThat(e.errors()).hasSize(1))
                .hasMessageContaining("loginz")
                .hasMessageStartingWith("Broken: ");
        assertThat(requestBodies).hasSize(1);
        assertThat(saved).extracting(Saved::source).containsExactly("Broken");
        assertThat(retrySleeps).isEmpty();
        assertThat(stats.snapshot()).doesNotContainKey(GitHubGraphQlClient.GRAPHQL_POINTS);
    }

    @Test
    void notFoundIsRecognisedByType() {
        replies.add(new Reply(200, Map.of(), """
                {"data": {"repository": null},
                 "errors": [{"type": "NOT_FOUND", "path": ["repository"],
                             "message": "Could not resolve to a Repository with the name 'nobody/nothing'."},
                            {"type": "OTHER", "message": "second"}]}"""));

        assertThatThrownBy(() -> client.execute("query RepoInfo { repository(owner: \"nobody\", name: \"nothing\") { id } }", Map.of()))
                .isInstanceOfSatisfying(GitHubGraphQlException.class, e -> {
                    assertThat(e.hasErrorType("NOT_FOUND")).isTrue();
                    assertThat(e.hasErrorType("RATE_LIMITED")).isFalse();
                })
                .hasMessageContaining("'nobody/nothing'.; second");
        assertThat(requestBodies).hasSize(1);
    }

    @Test
    void unauthorizedIsNotRetriedAndDoesNotLeakTheToken() {
        replies.add(new Reply(401, Map.of(), "{\"message\": \"Bad credentials\", \"status\": \"401\"}"));

        assertThatThrownBy(() -> client.execute(PING, Map.of()))
                .isInstanceOf(GitHubGraphQlException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("0001")
                .hasMessageNotContaining(TOKEN);
        assertThat(requestBodies).hasSize(1);
        assertThat(stats.snapshot()).containsEntry(GitHubGraphQlClient.HTTP_4XX, 1L);
    }

    @Test
    void forbiddenWithoutRateLimitSignsIsNotRetried() {
        replies.add(new Reply(403, limitHeaders(4000, Duration.ofMinutes(30)), "{\"message\": \"Resource not accessible by integration\"}"));

        assertThatThrownBy(() -> client.execute(PING, Map.of()))
                .isInstanceOf(GitHubGraphQlException.class)
                .hasMessageContaining("Resource not accessible");
        assertThat(requestBodies).hasSize(1);
        assertThat(guardSleeps).isEmpty();
    }

    @Test
    void exhaustedLimitWaitsUntilResetAndRetries() {
        replies.add(new Reply(403, limitHeaders(0, Duration.ofMinutes(30)), "{\"message\": \"API rate limit exceeded\"}"));
        replies.add(new Reply(200, limitHeaders(4999, Duration.ofMinutes(90)), OK_BODY));

        JsonNode data = client.execute(PING, Map.of());

        assertThat(data.path("viewer").path("login").asText()).isEqualTo("octocat");
        assertThat(guardSleeps).as("the guard sleeps until the reset").containsExactly(Duration.ofMinutes(30).plusSeconds(5));
        assertThat(retrySleeps).as("the policy adds only its short backoff").containsExactly(Duration.ofMillis(500));
        assertThat(stats.snapshot()).containsEntry(GitHubGraphQlClient.GRAPHQL_REQUESTS, 2L)
                .containsEntry(GitHubGraphQlClient.HTTP_4XX, 1L)
                .containsEntry(RetryPolicy.RETRIES, 1L)
                .containsEntry(RateLimitGuard.RATE_LIMIT_WAIT_S, 1805L);
    }

    @Test
    void rateLimitedErrorInA200BodyIsRetried() {
        replies.add(new Reply(200, limitHeaders(0, Duration.ofMinutes(10)),
                "{\"errors\": [{\"type\": \"RATE_LIMITED\", \"message\": \"API rate limit exceeded for user ID 1.\"}]}"));
        replies.add(new Reply(200, limitHeaders(4999, Duration.ofMinutes(70)), OK_BODY));

        client.execute(PING, Map.of());

        assertThat(guardSleeps).containsExactly(Duration.ofMinutes(10).plusSeconds(5));
        assertThat(requestBodies).hasSize(2);
    }

    @Test
    void secondaryLimitInA200BodyBacksOffForAMinute() {
        replies.add(new Reply(200, Map.of(), """
                {"errors": [{"message": "You have exceeded a secondary rate limit. Please wait a few minutes before you try again."}]}"""));
        replies.add(new Reply(200, Map.of(), OK_BODY));

        client.execute(PING, Map.of());

        assertThat(guardSleeps).containsExactly(Duration.ofMinutes(1));
        assertThat(retrySleeps).containsExactly(Duration.ofMillis(500));
        assertThat(stats.snapshot()).containsEntry(RateLimitGuard.RATE_LIMIT_WAIT_S, 60L);
    }

    @Test
    void secondaryLimitWithRetryAfterWaitsOnlyOnce() {
        replies.add(new Reply(403, Map.of("Retry-After", "30"), "{\"message\": \"You have exceeded a secondary rate limit.\"}"));
        replies.add(new Reply(200, Map.of(), OK_BODY));

        client.execute(PING, Map.of());

        assertThat(guardSleeps).containsExactly(Duration.ofSeconds(30));
        assertThat(retrySleeps).as("not 30 s a second time").containsExactly(Duration.ofMillis(500));
    }

    @Test
    void tooManyRequestsIsAlwaysARateLimit() {
        replies.add(new Reply(429, Map.of(), ""));
        replies.add(new Reply(200, Map.of(), OK_BODY));

        client.execute(PING, Map.of());

        assertThat(guardSleeps).containsExactly(Duration.ofMinutes(1));
        assertThat(stats.snapshot()).containsEntry(GitHubGraphQlClient.HTTP_4XX, 1L);
    }

    @Test
    void badGatewayIsRetriedAndFinallyGoesOut() {
        for (int i = 0; i < 5; i++) {
            replies.add(new Reply(502, Map.of(), "<html><body><h1>502 Bad Gateway</h1></body></html>"));
        }

        assertThatThrownBy(() -> client.execute(PING, Map.of()))
                .isInstanceOfSatisfying(RetryableHttpException.class, e -> assertThat(e.statusCode()).isEqualTo(502))
                .hasMessageContaining("Bad Gateway");
        assertThat(requestBodies).hasSize(5);
        assertThat(saved).as("only 200 bodies are recorded").isEmpty();
        assertThat(pool.remaining(TOKEN)).as("no rate limit headers, nothing recorded").isEqualTo(Integer.MAX_VALUE);
        assertThat(stats.snapshot()).containsEntry(GitHubGraphQlClient.GRAPHQL_REQUESTS, 5L)
                .containsEntry(GitHubGraphQlClient.HTTP_5XX, 5L)
                .containsEntry(RetryPolicy.RETRIES, 4L);
    }

    @Test
    void queryWithoutRateLimitCountsNoPoints() {
        replies.add(new Reply(200, Map.of(), "{\"data\": {\"viewer\": {\"login\": \"octocat\"}}}"));

        client.execute("query Me { viewer { login } }", null);

        assertThat(stats.snapshot()).containsEntry(GitHubGraphQlClient.GRAPHQL_POINTS, 0L);
        assertThat(saved).extracting(Saved::requestKey).containsExactly("{}");
    }

    @Test
    void operationNameComesFromTheQuery() {
        assertThat(GitHubGraphQlClient.operationName("query SearchRepos($q: String!) { search(query: $q) { repositoryCount } }"))
                .isEqualTo("SearchRepos");
        assertThat(GitHubGraphQlClient.operationName("# comment\nquery   Ping { viewer { login } }")).isEqualTo("Ping");
        assertThat(GitHubGraphQlClient.operationName("mutation AddStar { x }")).isEqualTo("AddStar");
        assertThat(GitHubGraphQlClient.operationName("query { viewer { login } }")).isEqualTo("anonymous");
        assertThat(GitHubGraphQlClient.operationName("{ viewer { login } }")).isEqualTo("anonymous");
    }

    private static Map<String, String> limitHeaders(int remaining, Duration untilReset) {
        return Map.of(
                "x-ratelimit-limit", "5000",
                "x-ratelimit-remaining", String.valueOf(remaining),
                "x-ratelimit-reset", String.valueOf(START.plus(untilReset).getEpochSecond()),
                "x-ratelimit-resource", "graphql");
    }
}
