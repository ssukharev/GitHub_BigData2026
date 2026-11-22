package ru.spbstu.ghanalytics.analytics;

import tech.tablesaw.api.Table;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Runs the queries kept in {@code src/main/resources/sql}. */
public final class Sql {

    private Sql() {
    }

    /** @param name path under {@code sql/} without {@code .sql}, e.g. {@code task3/per_capita} */
    public static Table table(Connection conn, String name) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(text(name))) {
            return Table.read().db(rs, name.substring(name.lastIndexOf('/') + 1));
        }
    }

    /**
     * Aggregates (World, EU, ...) are only known as such once collector/worldbank has run; before that the queries
     * would count them as countries (EU alone is 14% of all developers), so stop instead of producing quietly
     * wrong numbers.
     */
    public static void requireWorldBank(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM core.countries WHERE is_aggregate")) {
            rs.next();
            if (rs.getLong(1) == 0) {
                throw new IllegalStateException(
                        "core.countries has no World Bank aggregates: run collector/worldbank before the analysis");
            }
        }
    }

    static String text(String name) {
        try (InputStream in = Sql.class.getResourceAsStream("/sql/" + name + ".sql")) {
            if (in == null) {
                throw new IllegalArgumentException("No SQL " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
