package ru.spbstu.ghanalytics.innovationgraph;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;

/**
 * One collector run: downloads all four files, then replaces both tables in a single transaction, so the
 * database never holds a half-loaded snapshot. The run is logged in {@code core.collection_runs} with source
 * {@value #SOURCE}; country codes missing from {@code core.countries} go into its stats.
 */
public final class InnovationGraphLoader {

    static final String SOURCE = "innovation_graph";

    private final InnovationGraphClient client;
    private final String ref;
    private final DbConfig db;
    private final InnovationGraphRepository repository = new InnovationGraphRepository();
    private final ObjectMapper json = new ObjectMapper();

    /** @param ref the branch or commit the client downloads from, recorded with the run */
    public InnovationGraphLoader(InnovationGraphClient client, String ref, DbConfig db) {
        this.client = client;
        this.ref = ref;
        this.db = db;
    }

    /** @param unknownCodes country code to the number of loaded rows (stats and topics) using it */
    public record Result(long runId, int stats, int topics, SortedMap<String, Long> unknownCodes) {
    }

    public Result load() throws SQLException {
        try (Connection log = db.open()) {
            long runId = CollectionRuns.start(log, SOURCE, toJson(Map.of("ref", ref)));
            try {
                List<QuarterlyStats> stats = client.fetchQuarterlyStats();
                List<TopicStats> topics = client.fetchTopics();
                SortedMap<String, Long> unknown = write(stats, topics);

                Result result = new Result(runId, stats.size(), topics.size(), unknown);
                Map<String, Object> runStats = new LinkedHashMap<>();
                runStats.put("stats", result.stats());
                runStats.put("topics", result.topics());
                runStats.put("unknown_country_codes", unknown);
                CollectionRuns.succeed(log, runId, (long) stats.size() + topics.size(), toJson(runStats));
                return result;
            } catch (RuntimeException | SQLException e) {
                CollectionRuns.fail(log, runId, e);
                throw e;
            }
        }
    }

    private SortedMap<String, Long> write(List<QuarterlyStats> stats, List<TopicStats> topics) throws SQLException {
        try (Connection conn = db.open()) {
            conn.setAutoCommit(false);
            try {
                repository.replaceStats(conn, stats);
                repository.replaceTopics(conn, topics);

                List<String> codes = new ArrayList<>(stats.size() + topics.size());
                stats.forEach(s -> codes.add(s.countryCode()));
                topics.forEach(t -> codes.add(t.countryCode()));
                SortedMap<String, Long> unknown =
                        CountryCodeReport.unknownCodes(codes, repository.knownCountryCodes(conn));

                conn.commit();
                return unknown;
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
