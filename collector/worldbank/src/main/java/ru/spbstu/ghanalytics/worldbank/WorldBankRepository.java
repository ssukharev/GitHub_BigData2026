package ru.spbstu.ghanalytics.worldbank;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

/**
 * Upserts into {@code core.countries} and {@code core.population} (migration V2). Re-running with the same
 * data changes nothing; changed values are overwritten. The caller owns the transaction.
 */
final class WorldBankRepository {

    private static final int BATCH_SIZE = 1000;

    private static final String UPSERT_COUNTRY = """
            INSERT INTO core.countries (country_code, iso3_code, name, region, is_aggregate)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (country_code) DO UPDATE
            SET iso3_code = EXCLUDED.iso3_code, name = EXCLUDED.name,
                region = EXCLUDED.region, is_aggregate = EXCLUDED.is_aggregate""";

    private static final String UPSERT_POPULATION = """
            INSERT INTO core.population (country_code, year, population)
            VALUES (?, ?, ?)
            ON CONFLICT (country_code, year) DO UPDATE
            SET population = EXCLUDED.population""";

    int upsertCountries(Connection conn, List<Country> countries) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(UPSERT_COUNTRY)) {
            int pending = 0;
            for (Country c : countries) {
                st.setString(1, c.countryCode());
                st.setString(2, c.iso3Code());
                st.setString(3, c.name());
                if (c.region() == null) {
                    st.setNull(4, Types.VARCHAR);
                } else {
                    st.setString(4, c.region());
                }
                st.setBoolean(5, c.aggregate());
                st.addBatch();
                if (++pending == BATCH_SIZE) {
                    st.executeBatch();
                    pending = 0;
                }
            }
            st.executeBatch();
        }
        return countries.size();
    }

    int upsertPopulation(Connection conn, List<Population> population) throws SQLException {
        try (PreparedStatement st = conn.prepareStatement(UPSERT_POPULATION)) {
            int pending = 0;
            for (Population p : population) {
                st.setString(1, p.countryCode());
                st.setInt(2, p.year());
                st.setLong(3, p.population());
                st.addBatch();
                if (++pending == BATCH_SIZE) {
                    st.executeBatch();
                    pending = 0;
                }
            }
            st.executeBatch();
        }
        return population.size();
    }
}
