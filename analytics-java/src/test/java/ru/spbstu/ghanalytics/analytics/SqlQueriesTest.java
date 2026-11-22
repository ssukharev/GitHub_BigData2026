package ru.spbstu.ghanalytics.analytics;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.tablesaw.api.Table;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The SQL of tasks 1 and 3 on PostgreSQL in Docker with the real migrations and a small hand-made data set:
 * AA and BB are countries, EU is a World Bank aggregate, TW is a seed economy without population.
 */
class SqlQueriesTest {

    private DbConfig db;

    @BeforeEach
    void setUp() throws SQLException {
        db = TestDatabase.start();
        TestDatabase.reset(db);
        TestDatabase.execute(db, """
                INSERT INTO core.countries VALUES
                    ('AA', 'AAA', 'Alpha', 'North', false),
                    ('BB', 'BBB', 'Beta', 'South', false),
                    ('EU', 'EUU', 'European Union', NULL, true);
                INSERT INTO core.population VALUES
                    ('AA', 2024, 1000000), ('AA', 2025, 2000000), ('BB', 2025, 4000000);
                INSERT INTO core.innovation_graph VALUES
                    ('AA', 2025, 3, 1000, 2000, 1500),
                    ('AA', 2025, 4, 4000, 9000, 6000),
                    ('BB', 2025, 4, 2000, 3000, NULL),
                    ('EU', 2025, 4, 99999, 99999, 99999),
                    ('TW', 2025, 4, 500, 700, NULL);
                INSERT INTO core.innovation_graph_topics VALUES
                    ('AA', 2025, 3, 'llm', 5),
                    ('AA', 2025, 4, 'llm', 10),
                    ('AA', 2025, 4, 'python', 30),
                    ('EU', 2025, 4, 'llm', 1000);
                """);
    }

    private Table query(String name) throws SQLException {
        try (Connection conn = db.open()) {
            return Sql.table(conn, name);
        }
    }

    @Test
    void activityLeavesOutAggregatesAndCountsPushersOnlyWhereGitHubPublishedPushes() throws SQLException {
        Table q4 = query("task1/activity_by_quarter").where(t -> t.intColumn("quarter").isEqualTo(4));

        assertEquals(1, q4.rowCount());
        assertEquals(4000 + 2000 + 500, q4.longColumn("developers").get(0), "EU left out, TW kept");
        assertEquals(6000, q4.longColumn("git_pushes").get(0));
        assertEquals(4000, q4.longColumn("developers_with_pushes").get(0), "only AA has published pushes");
        assertEquals(3, q4.intColumn("countries").get(0));
    }

    @Test
    void topicsByQuarterLeaveOutAggregates() throws SQLException {
        Table topics = query("task1/topics_by_quarter");
        Table llm = topics.where(topics.stringColumn("topic").isEqualTo("llm").and(topics.intColumn("quarter").isEqualTo(4)));

        assertEquals(10, llm.longColumn("pushers").get(0));
    }

    @Test
    void topicsByCountryUseTheLatestQuarterOnly() throws SQLException {
        Table byCountry = query("task1/topics_by_country");

        assertEquals(List.of("AA", "AA"), byCountry.stringColumn("country_code").asList());
        assertTrue(byCountry.intColumn("quarter").asList().stream().allMatch(q -> q == 4));
        assertEquals(2025, byCountry.intColumn("year").get(0));
    }

    @Test
    void perCapitaUsesQ4AndThePopulationOfTheSameYear() throws SQLException {
        Table perCapita = query("task3/per_capita");

        assertEquals(List.of("AA", "BB"), perCapita.stringColumn("country_code").asList());
        assertEquals(2.0, perCapita.doubleColumn("developers_per_1000").get(0), 1e-9);   // 4000 / 2 000 000
        assertEquals(0.5, perCapita.doubleColumn("developers_per_1000").get(1), 1e-9);   // 2000 / 4 000 000
        assertEquals("North", perCapita.stringColumn("region").get(0));
    }

    @Test
    void noPopulationListsEconomiesLeftOutOfPerCapita() throws SQLException {
        Table noPopulation = query("task3/no_population");

        assertEquals(List.of("TW"), noPopulation.stringColumn("country_code").asList());
    }

    @Test
    void analysisStopsWithoutWorldBankAggregates() throws SQLException {
        TestDatabase.execute(db, "DELETE FROM core.countries WHERE is_aggregate");

        try (Connection conn = db.open()) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> Sql.requireWorldBank(conn));
            assertTrue(e.getMessage().contains("collector/worldbank"), e.getMessage());
        }
    }

    @Test
    void analysisRunsWithWorldBankAggregates() throws SQLException {
        try (Connection conn = db.open()) {
            Sql.requireWorldBank(conn);
        }
    }
}
