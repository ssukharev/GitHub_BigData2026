package ru.spbstu.ghanalytics.github;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class RawResponseRepositoryIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(TestDatabase.IMAGE);

    static JdbcTemplate jdbc;

    RawResponseRepository repository;

    @BeforeAll
    static void migrate() {
        jdbc = TestDatabase.migrate(postgres);
    }

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM raw.github_responses");
        repository = new RawResponseRepository(jdbc);
    }

    @Test
    void savesPayloadAsJsonb() {
        repository.save("SearchRepos", "{\"cursor\":null,\"first\":10,\"q\":\"stars:>=10\"}",
                "{\"data\":{\"search\":{\"repositoryCount\":42}}}");

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT source, request_key, fetched_at IS NOT NULL AS fetched,
                       (payload #>> '{data,search,repositoryCount}')::int AS count
                FROM raw.github_responses""");
        assertThat(row).containsEntry("source", "SearchRepos")
                .containsEntry("request_key", "{\"cursor\":null,\"first\":10,\"q\":\"stars:>=10\"}")
                .containsEntry("fetched", true)
                .containsEntry("count", 42);
    }

    @Test
    void nulEscapeDoesNotBreakTheInsert() {
        repository.save("SearchRepos", "{}", "{\"d\":\"x\\u0000y\"}");

        assertThat(jdbc.queryForObject("SELECT payload ->> 'd' FROM raw.github_responses", String.class))
                .isEqualTo("xy");
    }
}
