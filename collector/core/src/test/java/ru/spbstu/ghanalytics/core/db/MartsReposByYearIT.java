package ru.spbstu.ghanalytics.core.db;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code marts.repos_by_year} (R__marts_repos_by_year.sql) on a real PostgreSQL 16. The windows and
 * populations are those of V5: 2018-01 has 19,399 repositories, 2018-02 17,420, 2019-01 20,508.
 */
@Testcontainers
class MartsReposByYearIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        DriverManagerDataSource dataSource =
                new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM core.repos");
        jdbc.execute("SET TIME ZONE 'Europe/Moscow'");
    }

    /** has_* and license may be null; stars as given. */
    private static void repo(long id, String createdAt, int stars, Boolean tests, Boolean ci, Boolean docker,
                             String license) {
        jdbc.update("""
                INSERT INTO core.repos (id, node_id, full_name, owner_type, created_at, stars, forks, is_fork, is_archived,
                                        has_tests, has_ci, has_docker, license)
                VALUES (?, ?, ?, 'User', ?::timestamptz, ?, 0, false, false, ?, ?, ?, ?)""",
                id, "R_" + id, "a/r" + id, createdAt, stars, tests, ci, docker, license);
    }

    private static Map<String, Object> year(int year) {
        return jdbc.queryForMap("SELECT * FROM marts.repos_by_year WHERE year = ?", year);
    }

    private static void sample() {
        // 2018-01: two repositories of one window, weight 19,399 / 2 = 9,699.5 each
        repo(1, "2018-01-28T18:51:00Z", 10, true, false, false, "MIT");
        repo(2, "2018-01-28T18:52:00Z", 20, null, null, null, null);              // empty: flags unknown, no licence
        // 2018-02: one repository, weight 17,420
        repo(3, "2018-02-03T02:41:00Z", 30, false, true, false, "NOASSERTION");   // a licence file, type not recognized
        // 2019-01: one repository, weight 20,508, every flag unknown
        repo(4, "2019-01-25T00:51:00Z", 40, null, null, null, "Apache-2.0");
        // outside every window
        repo(5, "2017-06-01T00:00:00Z", 50, true, true, true, "MIT");
    }

    @Test
    void sharesAreWeightedOverKnownValuesOnly() {
        sample();

        Map<String, Object> y2018 = year(2018);

        assertThat(y2018).containsEntry("sample_n", 3L).containsEntry("population_n", 36_819L)
                .containsEntry("tests_known_n", 2L).containsEntry("ci_known_n", 2L).containsEntry("docker_known_n", 2L);
        assertThat((BigDecimal) y2018.get("tests_known_weight")).isEqualByComparingTo("27119.5");
        assertThat((BigDecimal) y2018.get("tests_share")).as("9,699.5 of 27,119.5 known, the empty one left out")
                .isEqualByComparingTo("0.3577");
        assertThat((BigDecimal) y2018.get("ci_share")).isEqualByComparingTo("0.6423");
        assertThat((BigDecimal) y2018.get("docker_share")).isEqualByComparingTo("0");
    }

    @Test
    void aRecognizedLicenceIsNeitherNullNorNoassertion() {
        sample();

        assertThat((BigDecimal) year(2018).get("license_recognized_share")).as("9,699.5 of 36,819")
                .isEqualByComparingTo("0.2634");
        assertThat((BigDecimal) year(2019).get("license_recognized_share")).isEqualByComparingTo("1");
    }

    @Test
    void aYearWithoutKnownValuesHasNoShareInsteadOfZero() {
        sample();

        Map<String, Object> y2019 = year(2019);

        assertThat(y2019).containsEntry("sample_n", 1L).containsEntry("population_n", 20_508L)
                .containsEntry("tests_known_n", 0L);
        assertThat(y2019.get("tests_share")).as("unknown, not 0").isNull();
        assertThat((BigDecimal) y2019.get("tests_known_weight")).isEqualByComparingTo("0");
    }

    @Test
    void theMedianOfStarsIsOfTheSampleAndOnlyTheSampleIsCounted() {
        sample();

        List<Integer> years = jdbc.queryForList("SELECT year FROM marts.repos_by_year ORDER BY year", Integer.class);

        assertThat(years).as("2017 is outside the frame").containsExactly(2018, 2019);
        assertThat(year(2018).get("sample_median_stars")).isEqualTo(20.0);
    }
}
