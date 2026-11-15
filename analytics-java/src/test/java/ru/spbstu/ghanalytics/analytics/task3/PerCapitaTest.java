package ru.spbstu.ghanalytics.analytics.task3;

import org.junit.jupiter.api.Test;
import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.IntColumn;
import tech.tablesaw.api.LongColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PerCapitaTest {

    /** Same columns as per_capita.sql. */
    private static Table data(Object[]... rows) {
        StringColumn code = StringColumn.create("country_code");
        StringColumn name = StringColumn.create("name");
        StringColumn region = StringColumn.create("region");
        IntColumn year = IntColumn.create("year");
        LongColumn developers = LongColumn.create("developers");
        LongColumn population = LongColumn.create("population");
        DoubleColumn perThousand = DoubleColumn.create("developers_per_1000");
        for (Object[] r : rows) {
            code.append((String) r[0]);
            name.append((String) r[0] + " name");
            region.append((String) r[1]);
            year.append((Integer) r[2]);
            developers.append((Long) r[3]);
            population.append((Long) r[4]);
            perThousand.append(1000.0 * (Long) r[3] / (Long) r[4]);
        }
        return Table.create("per_capita", code, name, region, year, developers, population, perThousand);
    }

    private static final Table DATA = data(
            new Object[]{"AA", "North", 2020, 10_000L, 2_000_000L},  //  5 per 1000
            new Object[]{"AA", "North", 2025, 40_000L, 2_000_000L},  // 20
            new Object[]{"BB", "North", 2020, 30_000L, 3_000_000L},  // 10
            new Object[]{"BB", "North", 2025, 45_000L, 3_000_000L},  // 15
            new Object[]{"CC", "South", 2025, 9_000L, 1_000_000L},   //  9, no 2020 row
            new Object[]{"MM", "South", 2020, 100L, 50_000L},        // micro-state
            new Object[]{"MM", "South", 2025, 5_000L, 50_000L});     // 100

    @Test
    void rankingSkipsSmallCountriesAndRanksBestFirst() {
        Table ranking = PerCapita.ranking(DATA, 2025, PerCapita.MIN_POPULATION);

        assertEquals(List.of("AA", "BB", "CC"), ranking.stringColumn("country_code").asList());
        assertEquals(List.of(1, 2, 3), ranking.intColumn("rank").asList());
        assertEquals(20.0, ranking.doubleColumn("developers_per_1000").get(0), 1e-9);
    }

    @Test
    void growthNeedsBothYears() {
        Table growth = PerCapita.growth(DATA, 2020, 2025, PerCapita.MIN_POPULATION);

        assertEquals(List.of("AA", "BB"), growth.stringColumn("country_code").asList());
        assertEquals(4.0, growth.doubleColumn("growth_ratio").get(0), 1e-9);
        assertEquals(1.5, growth.doubleColumn("growth_ratio").get(1), 1e-9);
        assertEquals(5.0, growth.doubleColumn("per_1000_2020").get(0), 1e-9);
    }

    @Test
    void regionsWeighByPopulation() {
        Table regions = PerCapita.regions(DATA);
        Table north2025 = regions.where(regions.stringColumn("region").isEqualTo("North")
                .and(regions.intColumn("year").isEqualTo(2025)));

        // (40 000 + 45 000) / (2 000 000 + 3 000 000) * 1000 = 17, not the mean of 20 and 15
        assertEquals(17.0, north2025.doubleColumn("developers_per_1000").get(0), 1e-9);
        assertEquals(2, north2025.intColumn("countries").get(0));
        // includes the micro-state: regions use every country
        Table south2025 = regions.where(regions.stringColumn("region").isEqualTo("South")
                .and(regions.intColumn("year").isEqualTo(2025)));
        assertEquals(1000.0 * 14_000 / 1_050_000, south2025.doubleColumn("developers_per_1000").get(0), 1e-9);
        assertEquals(List.of("North", "North", "South", "South"), regions.stringColumn("region").asList());
    }
}
