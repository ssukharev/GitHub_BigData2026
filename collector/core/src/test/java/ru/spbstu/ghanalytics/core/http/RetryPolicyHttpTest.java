package ru.spbstu.ghanalytics.core.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.spbstu.ghanalytics.core.run.RunStats;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The usage pattern for every source, end to end: shared client, {@code throwIfError} inside
 * {@code retry.execute}. A JDK {@link HttpServer} on a free local port plays the API, pauses are recorded.
 */
class RetryPolicyHttpTest {

    private final Queue<Reply> replies = new ConcurrentLinkedQueue<>();
    private final AtomicInteger hits = new AtomicInteger();
    private final List<Duration> sleeps = new ArrayList<>();
    private final RunStats stats = new RunStats();
    private final HttpClient http = HttpClientFactory.create(Duration.ofSeconds(2));
    private HttpServer server;
    private RetryPolicy retry;

    private record Reply(int status, String retryAfter, String body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);   // port 0: any free port
        server.createContext("/items", exchange -> {
            hits.incrementAndGet();
            Reply reply = replies.remove();
            if (reply.retryAfter() != null) {
                exchange.getResponseHeaders().add("Retry-After", reply.retryAfter());
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);   // -1: no body
            if (bytes.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
        retry = new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(30), sleeps::add, () -> 0.0, stats);
    }

    @AfterEach
    void stopServer() {
        http.close();
        server.stop(0);
    }

    @Test
    void retriesServerErrorsAndReturnsBody() {
        replies.add(new Reply(503, null, ""));
        replies.add(new Reply(502, null, "<html>Bad gateway</html>"));
        replies.add(new Reply(200, null, "[1, 2, 3]"));

        assertThat(get()).isEqualTo("[1, 2, 3]");
        assertThat(hits).hasValue(3);
        assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1));
        assertThat(stats.snapshot()).containsEntry(RetryPolicy.RETRIES, 2L);
    }

    @Test
    void waitsForRetryAfterOn429() {
        replies.add(new Reply(429, "3", "slow down"));
        replies.add(new Reply(200, null, "[]"));

        assertThat(get()).isEqualTo("[]");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(3));
    }

    @Test
    void doesNotRetryNotFound() {
        replies.add(new Reply(404, null, "{\"message\": \"Not Found\"}"));

        assertThatThrownBy(this::get)
                .isExactlyInstanceOf(HttpStatusException.class)
                .hasMessageContaining("404")
                .hasMessageContaining("Not Found");
        assertThat(hits).hasValue(1);
        assertThat(sleeps).isEmpty();
        assertThat(stats.snapshot()).isEmpty();
    }

    private String get() {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + server.getAddress().getPort() + "/items"))
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json")
                .GET()
                .build();
        return retry.execute(() -> {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            HttpStatusException.throwIfError(response);
            return response.body();
        });
    }
}
