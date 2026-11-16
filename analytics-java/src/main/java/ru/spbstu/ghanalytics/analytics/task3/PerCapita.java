package ru.spbstu.ghanalytics.analytics.task3;

import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.IntColumn;
import tech.tablesaw.api.LongColumn;
import tech.tablesaw.api.Row;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Calculations of task 3 on the rows of {@code sql/task3/per_capita.sql}: columns country_code, name, region,
 * year, developers, population, developers_per_1000.
 */
public final class PerCapita {

    /**
     * Rankings and growth leave out countries below this population: in micro-states a few thousand accounts
     * (or VPN exits) give per-capita values that say nothing about the country.
     */
    public static final long MIN_POPULATION = 1_000_000;

    /**
     * Economies whose developer count mostly reflects IP geolocation (VPN exits, regional offices of global
     * companies): 435 and 318 per 1000 inhabitants in 2025. Kept in the per-country tables, left out of regions.
     */
    public static final Set<String> IP_INFLATED = Set.of("HK", "SG");

    private PerCapita() {
    }

    /** Countries of one year with population >= {@code minPopulation}, best first, with a 1-based rank. */
    public static Table ranking(Table data, int year, long minPopulation) {
        Table rows = data.where(data.intColumn("year").isEqualTo(year)
                        .and(data.longColumn("population").isGreaterThanOrEqualTo(minPopulation)))
                .sortDescendingOn("developers_per_1000");
        IntColumn rank = IntColumn.create("rank");
        for (int i = 1; i <= rows.rowCount(); i++) {
            rank.append(i);
        }
        return rows.insertColumn(0, rank).setName("ranking_" + year);
    }

    /**
     * Developers per 1000 in {@code fromYear} and {@code toYear} and their ratio, for countries present in both
     * years with population >= {@code minPopulation} in {@code toYear}. Sorted by the ratio, fastest growth first.
     */
    public static Table growth(Table data, int fromYear, int toYear, long minPopulation) {
        Map<String, Double> before = new HashMap<>();
        for (Row row : data) {
            if (row.getInt("year") == fromYear) {
                before.put(row.getString("country_code"), row.getDouble("developers_per_1000"));
            }
        }

        StringColumn code = StringColumn.create("country_code");
        StringColumn name = StringColumn.create("name");
        StringColumn region = StringColumn.create("region");
        DoubleColumn from = DoubleColumn.create("per_1000_" + fromYear);
        DoubleColumn to = DoubleColumn.create("per_1000_" + toYear);
        DoubleColumn ratio = DoubleColumn.create("growth_ratio");
        for (Row row : data) {
            Double start = before.get(row.getString("country_code"));
            if (row.getInt("year") != toYear || row.getLong("population") < minPopulation
                    || start == null || start == 0) {
                continue;
            }
            double end = row.getDouble("developers_per_1000");
            code.append(row.getString("country_code"));
            name.append(row.getString("name"));
            region.append(row.getString("region"));
            from.append(start);
            to.append(end);
            ratio.append(end / start);
        }
        return Table.create("growth_" + fromYear + "_" + toYear, code, name, region, from, to, ratio)
                .sortDescendingOn("growth_ratio");
    }

    /**
     * Developers per 1000 by World Bank region and year: sum of developers over sum of population of the
     * countries that have both, so large countries weigh more (unlike an average of country values).
     * {@link #IP_INFLATED} economies and economies without a World Bank region are left out.
     */
    public static Table regions(Table data) {
        record Key(String region, int year) {
        }
        Map<Key, long[]> sums = new TreeMap<>((a, b) -> a.region().equals(b.region())
                ? Integer.compare(a.year(), b.year()) : a.region().compareTo(b.region()));
        for (Row row : data) {
            String region = row.getString("region");
            if (region.isEmpty() || IP_INFLATED.contains(row.getString("country_code"))) {
                continue;
            }
            long[] s = sums.computeIfAbsent(new Key(region, row.getInt("year")), k -> new long[3]);
            s[0]++;
            s[1] += row.getLong("developers");
            s[2] += row.getLong("population");
        }

        StringColumn regionColumn = StringColumn.create("region");
        IntColumn year = IntColumn.create("year");
        IntColumn countries = IntColumn.create("countries");
        LongColumn developers = LongColumn.create("developers");
        LongColumn population = LongColumn.create("population");
        DoubleColumn perThousand = DoubleColumn.create("developers_per_1000");
        sums.forEach((key, s) -> {
            regionColumn.append(key.region());
            year.append(key.year());
            countries.append((int) s[0]);
            developers.append(s[1]);
            population.append(s[2]);
            perThousand.append(1000.0 * s[1] / s[2]);
        });
        return Table.create("regions", regionColumn, year, countries, developers, population, perThousand);
    }
}
