package ru.spbstu.ghanalytics.analytics;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * PostgreSQL 16 in Docker (same image as docker-compose.yml) with the project's Flyway migrations applied.
 * One container per test JVM. Tests that use it are skipped when Docker is not running.
 */
final class TestDatabase {

    private static final Path MIGRATIONS = Path.of("../collector/core/src/main/resources/db/migration");

    private static PostgreSQLContainer container;

    private TestDatabase() {
    }

    static synchronized DbConfig start() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is not running");
        if (container == null) {
            if (!Files.isDirectory(MIGRATIONS)) {
                throw new IllegalStateException("Migrations not found at " + MIGRATIONS.toAbsolutePath());
            }
            container = new PostgreSQLContainer("postgres:16");
            container.start();
            Flyway.configure()
                    .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
                    .locations("filesystem:" + MIGRATIONS.toAbsolutePath())
                    .load()
                    .migrate();
        }
        return new DbConfig(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /** Back to the state right after the migrations: only the seed rows of core.countries stay. */
    static void reset(DbConfig db) throws SQLException {
        try (Connection conn = db.open(); Statement st = conn.createStatement()) {
            st.execute("TRUNCATE core.innovation_graph, core.innovation_graph_topics, core.population, core.collection_runs");
            st.execute("DELETE FROM core.countries WHERE country_code NOT IN ("
                    + "'AI','AQ','AX','BL','BQ','CK','FK','GF','GG','GP','IO','JE','MQ','MS','PM','RE','SH','TW','VA','YT')");
        }
    }

    static long count(DbConfig db, String sql) throws SQLException {
        try (Connection conn = db.open(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static String string(DbConfig db, String sql) throws SQLException {
        try (Connection conn = db.open(); Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    static void execute(DbConfig db, String sql) throws SQLException {
        try (Connection conn = db.open(); Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }
}
