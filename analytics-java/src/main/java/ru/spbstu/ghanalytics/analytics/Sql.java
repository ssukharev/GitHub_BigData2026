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
