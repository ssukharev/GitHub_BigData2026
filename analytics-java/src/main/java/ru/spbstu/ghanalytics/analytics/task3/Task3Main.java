package ru.spbstu.ghanalytics.analytics.task3;

import ru.spbstu.ghanalytics.analytics.DbConfig;
import ru.spbstu.ghanalytics.analytics.Sql;
import tech.tablesaw.api.Table;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** Task 3 tables, written as CSV to {@code out}. Started from {@link ru.spbstu.ghanalytics.analytics.Main}. */
public final class Task3Main {

    private static final int FIRST_YEAR = 2020;

    private Task3Main() {
    }

    public static void run(DbConfig db, Path out) throws SQLException, IOException {
        Table data;
        Table noPopulation;
        try (Connection conn = db.open()) {
            Sql.requireWorldBank(conn);
            data = Sql.table(conn, "task3/per_capita");
            noPopulation = Sql.table(conn, "task3/no_population");
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

        System.out.printf("Task 3: %d country-year rows, %d years; CSV written to %s%n",
                data.rowCount(), data.intColumn("year").countUnique(), out.toAbsolutePath());
        System.out.println(ranking.selectColumns("rank", "name", "developers", "population", "developers_per_1000")
                .first(10).print());
        System.out.println(regions.where(regions.intColumn("year").isEqualTo(lastYear)).sortDescendingOn("developers_per_1000")
                .selectColumns("region", "countries", "developers_per_1000").print());
        System.out.println(noPopulation.first(5).print());
    }
}
