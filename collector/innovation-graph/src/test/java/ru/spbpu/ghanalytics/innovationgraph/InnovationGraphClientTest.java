package ru.spbpu.ghanalytics.innovationgraph;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InnovationGraphClientTest {

    private static final String REF = "abc123";

    private HttpServer server;
    private InnovationGraphClient client;
    /** Statuses to return before serving the fixture, one per request. */
    private final Queue<Integer> failures = new ConcurrentLinkedQueue<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/innovationgraph/", this::handle);
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/innovationgraph/");
        client = new InnovationGraphClient(HttpClient.newHttpClient(), base, REF, 3, Duration.ZERO);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.add(path);
        Integer failure = failures.poll();
        if (failure != null) {
            respond(exchange, failure, "");
            return;
        }
        String prefix = "/innovationgraph/" + REF + "/data/";
        if (path.startsWith(prefix) && path.endsWith(".csv")) {
            String file = path.substring(prefix.length(), path.length() - ".csv".length());
            try {
                respond(exchange, 200, Fixtures.read(file));
                return;
            } catch (IllegalArgumentException e) {
                // unknown file, fall through to 404
            }
        }
        respond(exchange, 404, "");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void fetchesAndMergesThreeMetricFiles() {
        List<QuarterlyStats> stats = client.fetchQuarterlyStats();

        assertEquals(10, stats.size());
        assertEquals(List.of(
                "/innovationgraph/abc123/data/developers.csv",
                "/innovationgraph/abc123/data/repositories.csv",
                "/innovationgraph/abc123/data/git_pushes.csv"), requests);
    }

    @Test
    void fetchesTopics() {
        assertEquals(5, client.fetchTopics().size());
        assertEquals(List.of("/innovationgraph/abc123/data/topics.csv"), requests);
    }

    @Test
    void retriesServerErrorsAndRateLimit() {
        failures.add(503);
        failures.add(429);

        assertEquals(5, client.fetchTopics().size());
        assertEquals(3, requests.size());
    }

    @Test
    void givesUpAfterMaxAttempts() {
        failures.add(500);
        failures.add(502);
        failures.add(503);

        InnovationGraphException e = assertThrows(InnovationGraphException.class, client::fetchTopics);
        assertTrue(e.getMessage().contains("after 3 attempts"), e.getMessage());
        assertEquals(3, requests.size());
    }

    @Test
    void doesNotRetryClientErrors() {
        failures.add(404);

        assertThrows(InnovationGraphException.class, client::fetchTopics);
        assertEquals(1, requests.size());
    }
}
