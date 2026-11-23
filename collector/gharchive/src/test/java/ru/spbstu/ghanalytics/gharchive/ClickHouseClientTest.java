package ru.spbstu.ghanalytics.gharchive;

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
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.net.ssl.SSLSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A JDK {@link HttpServer} on a free local port plays ClickHouse; the retry policy never really sleeps. */
class ClickHouseClientTest {

    record Reply(int status, Map<String, String> headers, String body) {
    }

    private static final Map<String, String> FROM_CLICKHOUSE = Map.of("X-ClickHouse-Query-Id", "q1");

    private HttpServer server;
    private final HttpClient http = HttpClientFactory.create(Duration.ofSeconds(2));
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> lastQuery = new AtomicReference<>();   // raw query string
    private final AtomicReference<String> lastBody = new AtomicReference<>();    // SQL that reached the server
    private volatile Function<Integer, Reply> script;                             // what to answer to call n

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            int n = calls.incrementAndGet();
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Reply reply = script.apply(n);
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        http.close();
        server.stop(0);
    }

    private ClickHouseClient client(RunStats stats) {
        RetryPolicy retry = new RetryPolicy(3, Duration.ofMillis(1), Duration.ofMillis(5),
                d -> { }, () -> 0.0, stats);                       // no real sleep
        URI base = URI.create("http://localhost:" + server.getAddress().getPort() + "/");
        return new ClickHouseClient(http, retry, stats, base, "play");
    }

    private static Reply error(int status, int code, String body) {
        return new Reply(status, Map.of("X-ClickHouse-Exception-Code", String.valueOf(code)), body);
    }

    @Test
    void parsesTsvAndCountsRowsRead() {
        script = n -> new Reply(200,
                Map.of("X-ClickHouse-Query-Id", "q1", "X-ClickHouse-Summary", "{\"read_rows\":\"4303635911\"}"),
                "ts_hour\tevent_type\tevents\tcommits\n"
                        + "2024-01-01 00:00:00\tPushEvent\t71426\t85733\n"
                        + "2024-01-01 00:00:00\tWatchEvent\t9\t\\N\n");
        RunStats stats = new RunStats();

        QueryResult r = client(stats).query("SELECT 1", Map.of("from", "2024-01-01 00:00:00"));

        assertThat(r.rows()).hasSize(2);
        assertThat(r.get(r.rows().get(0), "events")).isEqualTo("71426");
        assertThat(r.getLongOrNull(r.rows().get(1), "commits")).isNull();
        assertThat(r.rowsRead()).isEqualTo(4_303_635_911L);
        assertThat(lastQuery.get()).contains("user=play").contains("wait_end_of_query=1")
                .contains("param_from=2024-01-01+00%3A00%3A00");
        assertThat(lastBody.get()).isEqualTo("SELECT 1");
        assertThat(stats.snapshot().get(ClickHouseClient.QUERIES)).isEqualTo(1L);
        assertThat(stats.snapshot().get(ClickHouseClient.ROWS_READ)).isEqualTo(4_303_635_911L);
    }

    @Test
    void quotaExceededGivesQuotaExceptionWithoutRetry() {
        script = n -> error(500, 201, "Code: 201. DB::Exception: Quota for user `play` for 3600s has been exceeded: "
                + "read_rows: 300.5 billion/300 billion. Interval will end at 2026-11-02 18:25:04. "
                + "Name of quota template: `explorer`. (QUOTA_EXCEEDED)");
        RunStats stats = new RunStats();

        assertThatThrownBy(() -> client(stats).query("SELECT 1", Map.of()))
                .isInstanceOfSatisfying(ClickHouseQuotaException.class, e -> {
                    assertThat(e.code()).isEqualTo(201);
                    assertThat(e.retryAt()).contains(Instant.parse("2026-11-02T18:25:04Z"));
                });
        assertThat(calls).hasValue(1);
        assertThat(stats.snapshot()).containsEntry(ClickHouseClient.HTTP_5XX, 1L).doesNotContainKey(RetryPolicy.RETRIES);
    }

    @Test
    void quotaExceptionWithoutWindowEndHasNoRetryAt() {
        script = n -> error(500, 201, "Code: 201. DB::Exception: Quota exceeded. (QUOTA_EXCEEDED)");

        assertThatThrownBy(() -> client(new RunStats()).query("SELECT 1", Map.of()))
                .isInstanceOfSatisfying(ClickHouseQuotaException.class, e -> assertThat(e.retryAt()).isEmpty());
    }

    @Test
    void sqlErrorWith500IsNotRetried() {
        script = n -> error(500, 10, "Code: 10. DB::Exception: Not found column event_type in block. (NOT_FOUND_COLUMN_IN_BLOCK)");

        assertThatThrownBy(() -> client(new RunStats()).query("SELECT 1", Map.of()))
                .isExactlyInstanceOf(ClickHouseException.class)
                .satisfies(e -> assertThat(((ClickHouseException) e).code()).isEqualTo(10))
                .hasMessageContaining("NOT_FOUND_COLUMN_IN_BLOCK");
        assertThat(calls).hasValue(1);
    }

    @Test
    void syntaxErrorWith400IsNotRetried() {
        script = n -> error(400, 62, "Code: 62. DB::Exception: Syntax error: failed at position 1 ('SELEC'). (SYNTAX_ERROR)");
        RunStats stats = new RunStats();

        assertThatThrownBy(() -> client(stats).query("SELEC 1", Map.of()))
                .isExactlyInstanceOf(ClickHouseException.class)
                .satisfies(e -> assertThat(((ClickHouseException) e).code()).isEqualTo(62));
        assertThat(calls).hasValue(1);
        assertThat(stats.snapshot()).containsEntry(ClickHouseClient.HTTP_4XX, 1L);
    }

    @Test
    void timeoutCode159IsNotRetried() {
        script = n -> error(408, 159, "Code: 159. DB::Exception: Timeout exceeded: elapsed 60001.928 ms, "
                + "maximum: 60000.000 ms. (TIMEOUT_EXCEEDED)");
        RunStats stats = new RunStats();

        assertThatThrownBy(() -> client(stats).query("SELECT 1", Map.of()))
                .isInstanceOfSatisfying(ClickHouseException.class, e -> assertThat(e.code()).isEqualTo(159));
        assertThat(calls).hasValue(1);
        assertThat(stats.snapshot()).containsEntry(ClickHouseClient.HTTP_4XX, 1L);
    }

    @Test
    void retriesBadGatewayAndCountsRetries() {
        script = n -> n <= 2
                ? new Reply(503, Map.of(), "<html>Service Unavailable</html>")
                : new Reply(200, FROM_CLICKHOUSE, "x\n1\n");
        RunStats stats = new RunStats();

        QueryResult r = client(stats).query("SELECT 1 AS x", Map.of());

        assertThat(r.getLong(r.rows().get(0), "x")).isEqualTo(1);
        assertThat(calls).hasValue(3);
        assertThat(stats.snapshot())
                .containsEntry(RetryPolicy.RETRIES, 2L)
                .containsEntry(ClickHouseClient.QUERIES, 3L)
                .containsEntry(ClickHouseClient.HTTP_5XX, 2L);
    }

    @Test
    void retriesTransientClickHouseCode() {
        script = n -> n == 1
                ? error(500, 209, "Code: 209. DB::NetException: Timeout exceeded while reading from socket. (SOCKET_TIMEOUT)")
                : new Reply(200, FROM_CLICKHOUSE, "x\n1\n");

        assertThat(client(new RunStats()).query("SELECT 1 AS x", Map.of()).rows()).hasSize(1);
        assertThat(calls).hasValue(2);
    }

    @Test
    void codeFromBodyWhenHeaderMissing() {
        script = n -> new Reply(500, Map.of(), "Code: 241. DB::Exception: Memory limit (for query) exceeded. (MEMORY_LIMIT_EXCEEDED)");

        assertThatThrownBy(() -> client(new RunStats()).query("SELECT 1", Map.of()))
                .isInstanceOfSatisfying(ClickHouseException.class, e -> assertThat(e.code()).isEqualTo(241));
        assertThat(calls).hasValue(1);
    }

    @Test
    void rejectsResponseWithoutQueryId() {
        script = n -> new Reply(200, Map.of(), "<html><title>ClickHouse Play</title></html>");

        assertThatThrownBy(() -> client(new RunStats()).query("SELECT 1", Map.of()))
                .isExactlyInstanceOf(ClickHouseException.class)
                .hasMessageContaining("X-ClickHouse-Query-Id");
        assertThat(calls).hasValue(1);
    }

    @Test
    void errorAfterRowsStartedIsNotTakenAsData() {
        script = n -> new Reply(200, FROM_CLICKHOUSE, "x\n1\n2\n__exception__\r\nabcd\r\n"
                + "Code: 159. DB::Exception: Timeout exceeded. (TIMEOUT_EXCEEDED)\r\n63 abcd\r\n__exception__\r\n");

        assertThatThrownBy(() -> client(new RunStats()).query("SELECT 1", Map.of()))
                .isInstanceOfSatisfying(ClickHouseException.class, e -> assertThat(e.code()).isEqualTo(159));
        assertThat(calls).hasValue(1);
    }

    @Test
    void estimateRowsSumsRowsColumn() {
        script = n -> new Reply(200, FROM_CLICKHOUSE, "database\ttable\tparts\trows\tmarks\n"
                + "default\tgithub_events\t12\t3995472421\t487729\n"
                + "default\tgithub_events\t3\t100\t1\n");
        String sql = "SELECT count() FROM github_events WHERE created_at >= {from:DateTime}\nFORMAT TSVWithNames";

        long rows = client(new RunStats()).estimateRows(sql, Map.of("from", "2024-01-01 00:00:00"));

        assertThat(rows).isEqualTo(3_995_472_521L);
        assertThat(lastBody.get()).isEqualTo("EXPLAIN ESTIMATE " + sql);
    }

    @Test
    void quotaLeftIsLimitMinusRead() {
        script = n -> new Reply(200, FROM_CLICKHOUSE, "max_read_rows\tread_rows\n300000000000\t7541611000\n");

        assertThat(client(new RunStats()).quotaLeftRows()).isEqualTo(300_000_000_000L - 7_541_611_000L);
        assertThat(lastBody.get()).contains("system.quota_usage");
    }

    @Test
    void quotaLeftWithoutLimitIsUnlimited() {
        script = n -> new Reply(200, FROM_CLICKHOUSE, "max_read_rows\tread_rows\n\\N\t5\n");

        assertThat(client(new RunStats()).quotaLeftRows()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void rateLimitOfProxyIsRetriedAfterRetryAfter() {
        script = n -> n == 1
                ? new Reply(429, Map.of("Retry-After", "0"), "Too Many Requests")
                : new Reply(200, FROM_CLICKHOUSE, "x\n1\n");

        assertThat(client(new RunStats()).query("SELECT 1 AS x", Map.of()).rows()).hasSize(1);
        assertThat(calls).hasValue(2);
    }

    @Test
    void buildUriEncodesParametersInStableOrder() {
        ClickHouseClient client = new ClickHouseClient(http, new RetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1),
                d -> { }, () -> 0.0), new RunStats(), URI.create("https://play.clickhouse.com"), "play");

        URI uri = client.buildUri(Map.of("to", "2025-01-01 00:00:00", "from", "2024-01-01 00:00:00"));

        assertThat(uri.toString()).isEqualTo("https://play.clickhouse.com/?user=play&wait_end_of_query=1"
                + "&param_from=2024-01-01+00%3A00%3A00&param_to=2025-01-01+00%3A00%3A00");
    }

    @Test
    void toExceptionClassifiesByCodeNotByStatus() {
        ClickHouseClient client = client(new RunStats());

        assertThat(client.toException(stubResponse(500, Map.of("X-ClickHouse-Exception-Code", List.of("202")), "")))
                .isInstanceOf(RetryableHttpException.class);
        assertThat(client.toException(stubResponse(500, Map.of("X-ClickHouse-Exception-Code", List.of("158")), "")))
                .isExactlyInstanceOf(ClickHouseException.class);
        assertThat(client.toException(stubResponse(502, Map.of(), "")))
                .isInstanceOf(RetryableHttpException.class);
        assertThat(client.toException(stubResponse(404, Map.of("X-ClickHouse-Exception-Code", List.of("60")), "")))
                .isExactlyInstanceOf(ClickHouseException.class);
    }

    @Test
    void quotaEndIsUtcOrNull() {
        assertThat(ClickHouseClient.quotaEnd("... Interval will end at 2026-11-02 18:25:04. ..."))
                .isEqualTo(Instant.parse("2026-11-02T18:25:04Z"));
        assertThat(ClickHouseClient.quotaEnd("no time here")).isNull();
        assertThat(ClickHouseClient.quotaEnd(null)).isNull();
    }

    @Test
    void exceptionCodeFallsBackToMinusOne() {
        HttpHeaders none = HttpHeaders.of(Map.of(), (a, b) -> true);

        assertThat(ClickHouseClient.exceptionCode(none, "<html>Bad Gateway</html>")).isEqualTo(-1);
        assertThat(ClickHouseClient.exceptionCode(none, "  Code: 62. DB::Exception")).isEqualTo(62);
        assertThat(ClickHouseClient.exceptionCode(
                HttpHeaders.of(Map.of("X-ClickHouse-Exception-Code", List.of("164")), (a, b) -> true), "Code: 1.")).isEqualTo(164);
    }

    private static HttpResponse<String> stubResponse(int status, Map<String, List<String>> headers, String body) {
        HttpHeaders h = HttpHeaders.of(headers, (a, b) -> true);
        return new StubResponse(status, h, body);
    }

    private record StubResponse(int statusCode, HttpHeaders headers, String body)
            implements HttpResponse<String> {
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
            return URI.create("http://localhost/");
        }

        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }
}
