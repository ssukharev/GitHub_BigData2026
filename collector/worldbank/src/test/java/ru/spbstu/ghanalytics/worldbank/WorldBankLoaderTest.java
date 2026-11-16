package ru.spbstu.ghanalytics.worldbank;

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
import java.sql.SQLException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full run against PostgreSQL in Docker with the real migrations; the API is served from fixtures. */
class WorldBankLoaderTest {

    private static final long SEED_COUNTRIES = 20;

    private DbConfig db;
    private HttpServer server;
    private WorldBankLoader loader;
    private volatile boolean populationFails;

    @BeforeEach
    void setUp() throws IOException, SQLException {
        db = TestDatabase.start();
        TestDatabase.reset(db);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/", this::handle);
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v2/");
        loader = new WorldBankLoader(new WorldBankClient(HttpClient.newHttpClient(), base, 1, Duration.ZERO), db);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String uri = exchange.getRequestURI().toString();
        String body = null;
        if (uri.startsWith("/v2/country?")) {
            body = Fixtures.read("countries.json");
        } else if (uri.contains("SP.POP.TOTL") && !populationFails) {
            body = Fixtures.read(uri.endsWith("&page=1") ? "population-page1.json" : "population-page2.json");
        }
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(body == null ? 400 : 200, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void loadsCountriesAndPopulation() throws SQLException {
        WorldBankLoader.Result result = loader.load(2018, 2026);

        assertEquals(6, result.countries());
        assertEquals(5, result.population());
        assertEquals(SEED_COUNTRIES + 6, TestDatabase.count(db, "SELECT count(*) FROM core.countries"));
        assertEquals(3, TestDatabase.count(db, "SELECT count(*) FROM core.countries WHERE is_aggregate"));
        assertEquals(8_215_424_893L, TestDatabase.count(db,
                "SELECT population FROM core.population WHERE country_code = '1W' AND year = 2025"));
        assertEquals("Europe & Central Asia",
                TestDatabase.string(db, "SELECT region FROM core.countries WHERE country_code = 'RU'"));

        assertEquals("success|11|worldbank", TestDatabase.string(db,
                "SELECT status || '|' || rows || '|' || source FROM core.collection_runs WHERE id = " + result.runId()));
        assertEquals(3, TestDatabase.count(db,
                "SELECT (stats->>'aggregates')::int FROM core.collection_runs WHERE id = " + result.runId()));
    }

    @Test
    void secondRunAddsNoDuplicates() throws SQLException {
        loader.load(2018, 2026);
        loader.load(2018, 2026);

        assertEquals(SEED_COUNTRIES + 6, TestDatabase.count(db, "SELECT count(*) FROM core.countries"));
        assertEquals(5, TestDatabase.count(db, "SELECT count(*) FROM core.population"));
        assertEquals(2, TestDatabase.count(db, "SELECT count(*) FROM core.collection_runs WHERE status = 'success'"));
    }

    @Test
    void overwritesChangedValues() throws SQLException {
        TestDatabase.execute(db, "INSERT INTO core.countries VALUES ('RU', 'RUS', 'Old name', 'Old region', false)");
        TestDatabase.execute(db, "INSERT INTO core.population VALUES ('RU', 2025, 1)");

        loader.load(2018, 2026);

        assertEquals("Russian Federation",
                TestDatabase.string(db, "SELECT name FROM core.countries WHERE country_code = 'RU'"));
        assertEquals(143_513_328L, TestDatabase.count(db,
                "SELECT population FROM core.population WHERE country_code = 'RU' AND year = 2025"));
    }

    @Test
    void failedDownloadIsLoggedAndWritesNothing() throws SQLException {
        populationFails = true;

        assertThrows(WorldBankApiException.class, () -> loader.load(2018, 2026));

        assertEquals(SEED_COUNTRIES, TestDatabase.count(db, "SELECT count(*) FROM core.countries"));
        assertEquals(0, TestDatabase.count(db, "SELECT count(*) FROM core.population"));
        String run = TestDatabase.string(db, "SELECT status || '|' || error FROM core.collection_runs");
        assertTrue(run.startsWith("failed|") && run.contains("HTTP 400"), run);
    }
}
