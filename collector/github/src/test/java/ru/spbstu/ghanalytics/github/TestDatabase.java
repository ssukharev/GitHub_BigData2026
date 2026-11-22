package ru.spbstu.ghanalytics.github;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Plumbing for repository ITs: the migrations from collector-core (they come with it on the
 * classpath) applied to a Testcontainers PostgreSQL, and a plain {@link JdbcTemplate} on it.
 * No Spring context is needed.
 */
final class TestDatabase {

    static final String IMAGE = "postgres:16";

    private TestDatabase() {
    }

    static JdbcTemplate migrate(PostgreSQLContainer<?> postgres) {
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        return new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }
}
