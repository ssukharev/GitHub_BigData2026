package ru.spbpu.ghanalytics.worldbank;

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

class WorldBankClientTest {

    private HttpServer server;
    private WorldBankClient client;
    /** Statuses to return before serving the fixture, one per request. */
    private final Queue<Integer> failures = new ConcurrentLinkedQueue<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/", this::handle);
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v2/");
        client = new WorldBankClient(HttpClient.newHttpClient(), base, 3, Duration.ZERO);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String uri = exchange.getRequestURI().toString();
        requests.add(uri);
        Integer failure = failures.poll();
        if (failure != null) {
            respond(exchange, failure, "");
            return;
        }
        String body;
        if (uri.startsWith("/v2/country?")) {
            body = Fixtures.read("countries.json");
        } else if (uri.contains("SP.POP.TOTL") && uri.endsWith("&page=1")) {
            body = Fixtures.read("population-page1.json");
        } else if (uri.contains("SP.POP.TOTL") && uri.endsWith("&page=2")) {
            body = Fixtures.read("population-page2.json");
        } else {
            respond(exchange, 404, "");
            return;
        }
        respond(exchange, 200, body);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void fetchesCountries() {
        List<Country> countries = client.fetchCountries();

        assertEquals(6, countries.size());
        assertEquals(1, requests.size());
    }

    @Test
    void walksAllPopulationPages() {
        List<Population> population = client.fetchPopulation(2018, 2026);

        assertEquals(5, population.size());
        assertEquals(2, requests.size());
        assertTrue(requests.getFirst().contains("date=2018:2026"), requests.getFirst());
    }

    @Test
    void retriesServerErrorsAndRateLimit() {
        failures.add(503);
        failures.add(429);

        assertEquals(6, client.fetchCountries().size());
        assertEquals(3, requests.size());
    }

    @Test
    void givesUpAfterMaxAttempts() {
        failures.add(500);
        failures.add(502);
        failures.add(503);

        WorldBankApiException e = assertThrows(WorldBankApiException.class, client::fetchCountries);
        assertTrue(e.getMessage().contains("after 3 attempts"), e.getMessage());
        assertEquals(3, requests.size());
    }

    @Test
    void doesNotRetryClientErrors() {
        failures.add(400);

        assertThrows(WorldBankApiException.class, client::fetchCountries);
        assertEquals(1, requests.size());
    }
}
