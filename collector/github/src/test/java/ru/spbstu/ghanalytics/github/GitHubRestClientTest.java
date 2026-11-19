package ru.spbstu.ghanalytics.github;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.spbstu.ghanalytics.core.http.HttpClientFactory;
import ru.spbstu.ghanalytics.core.http.HttpStatusException;
import ru.spbstu.ghanalytics.core.http.RetryPolicy;
import ru.spbstu.ghanalytics.core.http.RetryableHttpException;
import ru.spbstu.ghanalytics.core.run.RunStats;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link GitHubRestClient#decode} on byte arrays, and the HTTP handling against a local JDK server. */
class GitHubRestClientTest {

    private static final String TOKEN = "ghp_testtokentesttokentesttoken0002";
    private static final Instant START = Instant.parse("2026-10-25T09:00:00Z");

    private record Reply(int status, Map<String, String> headers, byte[] body) {
        Reply(int status, Map<String, String> headers, String body) {
            this(status, headers, body.getBytes(StandardCharsets.UTF_8));
        }
    }

    private record Seen(String path, String accept, String apiVersion, String authorization) {
    }

    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final List<Seen> seen = new ArrayList<>();
    private final TestClock clock = new TestClock(START);
    private final List<Duration> guardSleeps = new ArrayList<>();
    private final List<Duration> retrySleeps = new ArrayList<>();
    private final RunStats stats = new RunStats();
    private final HttpClient http = HttpClientFactory.create(Duration.ofSeconds(2));
    private HttpServer server;
    private GitHubRestClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            seen.add(new Seen(exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Accept"),
                    exchange.getRequestHeaders().getFirst("X-GitHub-Api-Version"),
                    exchange.getRequestHeaders().getFirst("Authorization")));
            Reply reply = replies.remove();
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(reply.status(), reply.body().length == 0 ? -1 : reply.body().length);
            if (reply.body().length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(reply.body());
                }
            }
            exchange.close();
        });
        server.start();

        RateLimitGuard guard = new RateLimitGuard(new TokenPool(List.of(TOKEN), clock), 50, clock, d -> {
            guardSleeps.add(d);
            clock.advance(d);
        }, stats);
        RetryPolicy retry = new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(30), retrySleeps::add, () -> 0.0, stats);
        client = new GitHubRestClient(http, retry, guard,
                URI.create("http://localhost:" + server.getAddress().getPort()), stats);
    }

    @AfterEach
    void tearDown() {
        http.close();
        server.stop(0);
    }

    @Test
    void decodesPlainUtf8() {
        assertThat(GitHubRestClient.decode("# Привет, README".getBytes(StandardCharsets.UTF_8))).isEqualTo("# Привет, README");
    }

    @Test
    void dropsUtf8ByteOrderMark() {
        byte[] body = concat(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, "# Title".getBytes(StandardCharsets.UTF_8));

        assertThat(GitHubRestClient.decode(body)).isEqualTo("# Title");
    }

    @Test
    void decodesUtf16LittleEndianWithByteOrderMark() {
        byte[] body = concat(new byte[]{(byte) 0xFF, (byte) 0xFE}, "# Привет".getBytes(StandardCharsets.UTF_16LE));

        assertThat(GitHubRestClient.decode(body)).isEqualTo("# Привет");
    }

    @Test
    void decodesUtf16BigEndianWithByteOrderMark() {
        byte[] body = concat(new byte[]{(byte) 0xFE, (byte) 0xFF}, "# Привет".getBytes(StandardCharsets.UTF_16BE));

        assertThat(GitHubRestClient.decode(body)).isEqualTo("# Привет");
    }

    @Test
    void removesNulCharacters() {
        assertThat(GitHubRestClient.decode("a\0b".getBytes(StandardCharsets.UTF_8))).isEqualTo("ab");
    }

    @Test
    void emptyBodyGivesEmptyText() {
        assertThat(GitHubRestClient.decode(new byte[0])).isEmpty();
    }

    @Test
    void readmeReturnsDecodedTextAndSendsTheRightHeaders() {
        replies.add(new Reply(200, limitHeaders(4999), concat(new byte[]{(byte) 0xFF, (byte) 0xFE},
                "# Заголовок".getBytes(StandardCharsets.UTF_16LE))));

        assertThat(client.readme("owner/repo")).contains("# Заголовок");
        assertThat(seen).containsExactly(new Seen("/repos/owner/repo/readme", GitHubRestClient.ACCEPT_RAW,
                "2022-11-28", "Bearer " + TOKEN));
        assertThat(stats.snapshot()).containsEntry(GitHubRestClient.REST_REQUESTS, 1L)
                .doesNotContainKey(GitHubGraphQlClient.HTTP_4XX);
    }

    @Test
    void missingReadmeIsEmptyAndNotAnError() {
        replies.add(new Reply(404, limitHeaders(4998), "{\"message\": \"Not Found\"}"));

        assertThat(client.readme("owner/empty")).isEmpty();
        assertThat(stats.snapshot()).containsEntry(GitHubRestClient.REST_REQUESTS, 1L)
                .doesNotContainKey(GitHubGraphQlClient.HTTP_4XX);
        assertThat(retrySleeps).isEmpty();
    }

    @Test
    void exhaustedLimitWaitsForTheResetOfTheRestBucket() {
        replies.add(new Reply(403, limitHeaders(0), "{\"message\": \"API rate limit exceeded for user ID 1.\"}"));
        replies.add(new Reply(200, limitHeaders(4999), "# ok"));

        assertThat(client.readme("owner/repo")).contains("# ok");
        assertThat(guardSleeps).containsExactly(Duration.ofMinutes(20).plusSeconds(5));
        assertThat(stats.snapshot()).containsEntry(GitHubRestClient.REST_REQUESTS, 2L)
                .containsEntry(GitHubGraphQlClient.HTTP_4XX, 1L);
    }

    @Test
    void aLowBucketWaitsForTheResetBeforeTheNextRequestInsteadOfFailing() {
        replies.add(new Reply(200, limitHeaders(10), "[]"));
        replies.add(new Reply(200, limitHeaders(4999), "[]"));

        client.get("/repos/owner/one/stats/contributors", GitHubRestClient.ACCEPT_JSON);
        assertThat(guardSleeps).isEmpty();
        client.get("/repos/owner/two/stats/contributors", GitHubRestClient.ACCEPT_JSON);

        assertThat(guardSleeps).as("below 50 left: sleep until the reset, then go on")
                .containsExactly(Duration.ofMinutes(20).plusSeconds(5));
        assertThat(seen).hasSize(2);
        assertThat(retrySleeps).isEmpty();
        assertThat(stats.snapshot()).containsEntry(RateLimitGuard.RATE_LIMIT_WAIT_S, 1205L);
    }

    @Test
    void noContentAndNotFoundAreReturnedAsTheyAre() {
        replies.add(new Reply(204, limitHeaders(4999), ""));
        replies.add(new Reply(404, limitHeaders(4998), "{\"message\": \"Not Found\"}"));

        assertThat(client.get("/repos/owner/empty/stats/contributors", GitHubRestClient.ACCEPT_JSON).statusCode())
                .isEqualTo(204);
        assertThat(client.get("/repos/owner/gone/stats/contributors", GitHubRestClient.ACCEPT_JSON).statusCode())
                .isEqualTo(404);
        assertThat(retrySleeps).isEmpty();
    }

    @Test
    void aContributorListTooLargeIsForbiddenAtOnceNotARateLimit() {
        replies.add(new Reply(403, limitHeaders(4000), "{\"message\": \"The history or contributor list is too large to "
                + "list contributors for this repository via the API.\", \"status\": \"403\"}"));

        assertThatThrownBy(() -> client.get("/repos/torvalds/linux/contributors?per_page=1&anon=1", GitHubRestClient.ACCEPT_JSON))
                .isExactlyInstanceOf(HttpStatusException.class)
                .satisfies(e -> assertThat(((HttpStatusException) e).statusCode()).isEqualTo(403));
        assertThat(seen).hasSize(1);
        assertThat(guardSleeps).isEmpty();
        assertThat(retrySleeps).isEmpty();
    }

    @Test
    void forbiddenWithoutRateLimitSignsIsNotRetried() {
        replies.add(new Reply(403, limitHeaders(4000), "{\"message\": \"Repository access blocked\"}"));

        assertThatThrownBy(() -> client.readme("owner/blocked"))
                .isExactlyInstanceOf(HttpStatusException.class)
                .hasMessageContaining("Repository access blocked");
        assertThat(seen).hasSize(1);
    }

    @Test
    void unauthorizedIsNotRetriedAndDoesNotLeakTheToken() {
        replies.add(new Reply(401, Map.of(), "{\"message\": \"Bad credentials\"}"));

        assertThatThrownBy(() -> client.readme("owner/repo"))
                .isExactlyInstanceOf(HttpStatusException.class)
                .hasMessageContaining("0002")
                .hasMessageNotContaining(TOKEN);
        assertThat(seen).hasSize(1);
    }

    @Test
    void serverErrorsAreRetried() {
        replies.add(new Reply(500, Map.of(), ""));
        replies.add(new Reply(503, Map.of("Retry-After", "2"), "unavailable"));
        replies.add(new Reply(200, Map.of(), "# ok"));

        assertThat(client.readme("owner/repo")).contains("# ok");
        assertThat(retrySleeps).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(2));
        assertThat(stats.snapshot()).containsEntry(GitHubGraphQlClient.HTTP_5XX, 2L);
    }

    @Test
    void serverErrorsGoOutAfterTheLastAttempt() {
        for (int i = 0; i < 5; i++) {
            replies.add(new Reply(502, Map.of(), "<html>502</html>"));
        }

        assertThatThrownBy(() -> client.readme("owner/repo")).isInstanceOf(RetryableHttpException.class);
        assertThat(seen).hasSize(5);
    }

    @Test
    void stringGetReturnsTheJsonBody() {
        replies.add(new Reply(202, Map.of(), "{}"));

        assertThat(client.get("/repos/owner/repo/stats/commit_activity", GitHubRestClient.ACCEPT_JSON).statusCode())
                .isEqualTo(202);
        assertThat(seen.get(0).accept()).isEqualTo(GitHubRestClient.ACCEPT_JSON);
    }

    private static Map<String, String> limitHeaders(int remaining) {
        return Map.of(
                "x-ratelimit-limit", "5000",
                "x-ratelimit-remaining", String.valueOf(remaining),
                "x-ratelimit-reset", String.valueOf(START.plus(Duration.ofMinutes(20)).getEpochSecond()),
                "x-ratelimit-resource", "core");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(a);
        out.writeBytes(b);
        return out.toByteArray();
    }
}
