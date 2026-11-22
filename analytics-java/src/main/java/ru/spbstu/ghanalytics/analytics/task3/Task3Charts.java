package ru.spbstu.ghanalytics.analytics.task3;

import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategorySeries;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.style.Styler;
import ru.spbstu.ghanalytics.analytics.Charts;
import tech.tablesaw.api.Row;
import tech.tablesaw.api.Table;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Article figures of task 3, from the tables built by {@link Task3Main}. */
final class Task3Charts {

    private static final Map<String, String> REGIONS = new LinkedHashMap<>();

    static {
        // fixed order = fixed color slot
        REGIONS.put("North America", "Северная Америка");
        REGIONS.put("Europe & Central Asia", "Европа и Центральная Азия");
        REGIONS.put("Latin America & Caribbean", "Латинская Америка и Карибский бассейн");
        REGIONS.put("East Asia & Pacific", "Восточная Азия и Тихоокеанский регион");
        REGIONS.put("South Asia", "Южная Азия");
        REGIONS.put("Middle East, North Africa, Afghanistan & Pakistan",
                "Ближний Восток, Северная Африка, Афганистан и Пакистан");
        REGIONS.put("Sub-Saharan Africa", "Африка южнее Сахары");
    }

    private static final Map<String, String> COUNTRIES = new LinkedHashMap<>();

    static {
        COUNTRIES.put("US", "США");
        COUNTRIES.put("DE", "Германия");
        COUNTRIES.put("RU", "Россия");
        COUNTRIES.put("BR", "Бразилия");
        COUNTRIES.put("IN", "Индия");
        COUNTRIES.put("CN", "Китай");
        COUNTRIES.put("NG", "Нигерия");
    }

    private Task3Charts() {
    }

    static List<Path> write(Table data, Table ranking, Table regions, Path dir) throws IOException {
        return List.of(
                Charts.save(top(ranking, 20), dir, "task3_top20"),
                Charts.save(regions(regions), dir, "task3_regions"),
                Charts.save(countries(data), dir, "task3_countries"));
    }

    /** Top countries without the IP-inflated ones: next to Hong Kong (435) the rest would look flat. */
    private static CategoryChart top(Table ranking, int n) {
        int year = ranking.intColumn("year").get(0);
        CategoryChart chart = Charts.bars("Разработчики GitHub на 1000 жителей, " + year + ": топ-" + n
                + " (без Гонконга и Сингапура)", "", "разработчиков на 1000 жителей");
        List<String> names = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        for (Row row : ranking) {
            if (names.size() == n) {
                break;
            }
            if (!PerCapita.IP_INFLATED.contains(row.getString("country_code"))) {
                names.add(Charts.countryName(row.getString("country_code"), row.getString("name")));
                values.add(row.getDouble("developers_per_1000"));
            }
        }
        CategorySeries series = chart.addSeries("страны от 1 млн жителей", names, values);
        series.setFillColor(Charts.SERIES[0]);
        chart.getStyler().setLegendVisible(false);
        chart.getStyler().setXAxisLabelRotation(60);
        chart.getStyler().setYAxisMin(0.0);
        return chart;
    }

    /**
     * Known regions keep their fixed color slot; a region the World Bank renamed or added is still drawn, under
     * its English name in the next free slot, and reported, so a line never silently disappears.
     */
    private static XYChart regions(Table regions) {
        XYChart chart = Charts.lines("GitHub по регионам, без Гонконга и Сингапура",
                "", "разработчиков на 1000 жителей");
        Map<String, String> names = new LinkedHashMap<>(REGIONS);
        for (String region : new TreeSet<>(regions.stringColumn("region").asSet())) {
            if (!names.containsKey(region)) {
                System.err.println("Task 3 chart: World Bank region '" + region + "' has no Russian name in Task3Charts");
                names.put(region, region);
            }
        }
        if (names.size() > Charts.SERIES.length) {
            throw new IllegalStateException(names.size() + " regions, only " + Charts.SERIES.length + " colors");
        }
        int slot = 0;
        for (Map.Entry<String, String> region : names.entrySet()) {
            Table r = regions.where(regions.stringColumn("region").isEqualTo(region.getKey())).sortAscendingOn("year");
            if (!r.isEmpty()) {
                Charts.line(chart, region.getValue(), years(r), r.doubleColumn("developers_per_1000").asDoubleArray(), slot);
            }
            slot++;
        }
        legendRight(chart);
        return chart;
    }

    private static XYChart countries(Table data) {
        XYChart chart = Charts.lines("Разработчики GitHub на 1000 жителей: крупные страны", "", "разработчиков на 1000 жителей");
        int slot = 0;
        for (Map.Entry<String, String> country : COUNTRIES.entrySet()) {
            Table c = data.where(data.stringColumn("country_code").isEqualTo(country.getKey())).sortAscendingOn("year");
            if (!c.isEmpty()) {
                Charts.line(chart, country.getValue(), years(c), c.doubleColumn("developers_per_1000").asDoubleArray(), slot);
            }
            slot++;
        }
        legendRight(chart);
        return chart;
    }

    private static double[] years(Table t) {
        return t.intColumn("year").asList().stream().mapToDouble(Integer::doubleValue).toArray();
    }

    private static void legendRight(XYChart chart) {
        chart.getStyler().setLegendPosition(Styler.LegendPosition.OutsideE);
        chart.getStyler().setLegendLayout(Styler.LegendLayout.Vertical);
        chart.getStyler().setXAxisDecimalPattern("0");
        chart.getStyler().setYAxisMin(0.0);
    }
}
