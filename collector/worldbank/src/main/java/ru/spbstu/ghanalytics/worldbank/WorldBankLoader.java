package ru.spbstu.ghanalytics.worldbank;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * One collector run: downloads countries and population, then writes both in a single transaction, so the
 * database never holds a half-loaded run. The run is logged in {@code core.collection_runs} with source
 * {@value #SOURCE}.
 */
public final class WorldBankLoader {

    static final String SOURCE = "worldbank";

    private final WorldBankClient client;
    private final DbConfig db;
    private final WorldBankRepository repository = new WorldBankRepository();
    private final ObjectMapper json = new ObjectMapper();

    public WorldBankLoader(WorldBankClient client, DbConfig db) {
        this.client = client;
        this.db = db;
    }

    public record Result(long runId, int countries, int population) {
    }

    public Result load(int fromYear, int toYear) throws SQLException {
        try (Connection log = db.open()) {
            long runId = CollectionRuns.start(log, SOURCE, toJson(Map.of(
                    "indicator", WorldBankClient.POPULATION_INDICATOR, "from_year", fromYear, "to_year", toYear)));
            try {
                List<Country> countries = client.fetchCountries();
                List<Population> population = client.fetchPopulation(fromYear, toYear);
                write(countries, population);

                Result result = new Result(runId, countries.size(), population.size());
                CollectionRuns.succeed(log, runId, countries.size() + population.size(), toJson(Map.of(
                        "countries", result.countries(),
                        "aggregates", countries.stream().filter(Country::aggregate).count(),
                        "population", result.population())));
                return result;
            } catch (RuntimeException | SQLException | Error e) {
                CollectionRuns.recordFailure(log, runId, e);
                throw e;
            }
        }
    }

    private void write(List<Country> countries, List<Population> population) throws SQLException {
        try (Connection conn = db.open()) {
            conn.setAutoCommit(false);
            try {
                // countries first: core.population references them
                repository.upsertCountries(conn, countries);
                repository.upsertPopulation(conn, population);
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            }
        }
    }

    private String toJson(Map<String, ?> value) {
        try {
            return json.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
