package ru.spbstu.ghanalytics.analytics.task3;

import ru.spbstu.ghanalytics.analytics.DbConfig;
import tech.tablesaw.api.Table;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Task 3 tables from the database in {@code .env}, written as CSV to {@code results/task3} (or the first argument).
 * <pre>
 * cd analytics-java
 * mvn -q compile exec:java
 * </pre>
 * Needs data loaded by collector/worldbank and collector/innovation-graph.
 */
public final class Task3Main {

    private static final int FIRST_YEAR = 2020;

    private Task3Main() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args.length > 0 ? args[0] : "results/task3");
        DbConfig db = DbConfig.fromEnvironment();

        Table data;
        Table noPopulation;
        try (Connection conn = db.open()) {
            requireWorldBank(conn);
            data = query(conn, "per_capita");
            noPopulation = query(conn, "no_population");
        }
        if (data.isEmpty()) {
            throw new IllegalStateException("No data: load collector/worldbank and collector/innovation-graph first");
        }
        int lastYear = (int) data.intColumn("year").max();

        Table ranking = PerCapita.ranking(data, lastYear, PerCapita.MIN_POPULATION);
        Table growth = PerCapita.growth(data, FIRST_YEAR, lastYear, PerCapita.MIN_POPULATION);
        Table regions = PerCapita.regions(data);

        Files.createDirectories(out);
        data.write().csv(out.resolve("per_capita.csv").toFile());
        ranking.write().csv(out.resolve("ranking_" + lastYear + ".csv").toFile());
        growth.write().csv(out.resolve("growth_" + FIRST_YEAR + "_" + lastYear + ".csv").toFile());
        regions.write().csv(out.resolve("regions.csv").toFile());
        noPopulation.write().csv(out.resolve("no_population.csv").toFile());

        System.out.printf("%d country-year rows, %d years; CSV written to %s%n",
                data.rowCount(), data.intColumn("year").countUnique(), out.toAbsolutePath());
        System.out.println(ranking.selectColumns("rank", "name", "developers", "population", "developers_per_1000")
                .first(10).print());
        System.out.println(regions.where(regions.intColumn("year").isEqualTo(lastYear)).sortDescendingOn("developers_per_1000")
                .selectColumns("region", "countries", "developers_per_1000").print());
        System.out.println(noPopulation.first(5).print());
    }

    /**
     * Aggregates (World, EU, ...) are only known as such once collector/worldbank has run; before that the queries
     * would count them as countries, so stop instead of producing quietly wrong numbers.
     */
    private static void requireWorldBank(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM core.countries WHERE is_aggregate")) {
            rs.next();
            if (rs.getLong(1) == 0) {
                throw new IllegalStateException(
                        "core.countries has no World Bank aggregates: run collector/worldbank before the analysis");
            }
        }
    }

    private static Table query(Connection conn, String name) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql(name))) {
            return Table.read().db(rs, name);
        }
    }

    private static String sql(String name) {
        try (InputStream in = Task3Main.class.getResourceAsStream("/sql/task3/" + name + ".sql")) {
            if (in == null) {
                throw new IllegalArgumentException("No SQL " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
