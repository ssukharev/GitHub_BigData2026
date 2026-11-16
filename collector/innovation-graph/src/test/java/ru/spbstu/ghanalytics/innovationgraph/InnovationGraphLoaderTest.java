package ru.spbstu.ghanalytics.innovationgraph;

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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Full run against PostgreSQL in Docker with the real migrations; the CSV files are served from fixtures. */
class InnovationGraphLoaderTest {

    private static final String REF = "abc123";

    private DbConfig db;
    private HttpServer server;
    private InnovationGraphLoader loader;
    private volatile boolean topicsFail;

    @BeforeEach
    void setUp() throws IOException, SQLException {
        db = TestDatabase.start();
        TestDatabase.reset(db);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/innovationgraph/", this::handle);
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/innovationgraph/");
        InnovationGraphClient client = new InnovationGraphClient(HttpClient.newHttpClient(), base, REF, 1, Duration.ZERO);
        loader = new InnovationGraphLoader(client, REF, db);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String prefix = "/innovationgraph/" + REF + "/data/";
        String file = path.startsWith(prefix) ? path.substring(prefix.length()).replace(".csv", "") : "";
        String body = file.equals("topics") && topicsFail ? null : Fixtures.read(file);
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void loadsStatsAndTopicsAndReportsUnknownCodes() throws SQLException {
        // TW is in the seed of migration V2; the other fixture economies are not loaded yet
        InnovationGraphLoader.Result result = loader.load();

        assertEquals(10, result.stats());
        assertEquals(5, result.topics());
        assertEquals(10, TestDatabase.count(db, "SELECT count(*) FROM core.innovation_graph"));
        assertEquals(5, TestDatabase.count(db, "SELECT count(*) FROM core.innovation_graph_topics"));
        assertEquals(1, TestDatabase.count(db, "SELECT count(*) FROM core.innovation_graph WHERE git_pushes IS NULL"));
        assertEquals(35_064_239L, TestDatabase.count(db,
                "SELECT developers FROM core.innovation_graph WHERE country_code = 'US' AND year = 2026 AND quarter = 2"));

        // US: 2 stats rows + 5 topic rows
        assertEquals(Map.of("AD", 1L, "EU", 2L, "RU", 2L, "US", 7L, "XK", 1L), result.unknownCodes());
        assertEquals("success|15", TestDatabase.string(db,
                "SELECT status || '|' || rows FROM core.collection_runs WHERE id = " + result.runId()));
        assertEquals(REF, TestDatabase.string(db,
                "SELECT params->>'ref' FROM core.collection_runs WHERE id = " + result.runId()));
        assertEquals(7, TestDatabase.count(db,
                "SELECT (stats->'unknown_country_codes'->>'US')::int FROM core.collection_runs WHERE id = " + result.runId()));
    }

    @Test
    void knownCodesAreNotReported() throws SQLException {
        TestDatabase.execute(db, """
                INSERT INTO core.countries VALUES
                    ('AD', 'AND', 'Andorra', 'Europe & Central Asia', false),
                    ('EU', 'EUU', 'European Union', NULL, true),
                    ('RU', 'RUS', 'Russian Federation', 'Europe & Central Asia', false),
                    ('US', 'USA', 'United States', 'North America', false),
                    ('XK', 'XKX', 'Kosovo', 'Europe & Central Asia', false)""");

        assertTrue(loader.load().unknownCodes().isEmpty());
    }

    @Test
    void reloadReplacesTheSnapshot() throws SQLException {
        // a row that GitHub no longer publishes
        TestDatabase.execute(db, "INSERT INTO core.innovation_graph VALUES ('ZZ', 2020, 1, 500, NULL, NULL)");

        loader.load();
        loader.load();

        assertEquals(0, TestDatabase.count(db, "SELECT count(*) FROM core.innovation_graph WHERE country_code = 'ZZ'"));
        assertEquals(10, TestDatabase.count(db, "SELECT count(*) FROM core.innovation_graph"));
        assertEquals(5, TestDatabase.count(db, "SELECT count(*) FROM core.innovation_graph_topics"));
        assertEquals(2, TestDatabase.count(db, "SELECT count(*) FROM core.collection_runs WHERE status = 'success'"));
    }

    @Test
    void failedDownloadIsLoggedAndKeepsOldData() throws SQLException {
        loader.load();
        topicsFail = true;

        assertThrows(InnovationGraphException.class, loader::load);

        assertEquals(10, TestDatabase.count(db, "SELECT count(*) FROM core.innovation_graph"));
        assertEquals(5, TestDatabase.count(db, "SELECT count(*) FROM core.innovation_graph_topics"));
        String run = TestDatabase.string(db,
                "SELECT status || '|' || error FROM core.collection_runs ORDER BY id DESC LIMIT 1");
        assertTrue(run.startsWith("failed|") && run.contains("HTTP 404"), run);
    }
}
