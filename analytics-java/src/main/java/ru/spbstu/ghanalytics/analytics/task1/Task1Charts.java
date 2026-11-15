package ru.spbstu.ghanalytics.analytics.task1;

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
import java.util.List;
import java.util.Locale;

/** Article figures of task 1, from the tables built by {@link Task1Main}. */
final class Task1Charts {

    private static final double CHATGPT_X = AiActivity.decimalYear(Task1Main.CHATGPT);
    private static final double CODING_AGENTS_X = AiActivity.decimalYear(Task1Main.CODING_AGENTS);
    /** Room right of the last quarter for the value labels; small enough that no empty next year gets a tick. */
    private static final double RIGHT_MARGIN = 0.4;
    private static final double PLOT_WIDTH_PX = 840;
    private static final double CHAR_PX = 6.5;

    private Task1Charts() {
    }

    /** @param countryQuarter quarter of the by-country topic data */
    static List<Path> write(Table activity, Table byQuarter, Table byCountry, String countryQuarter, Path dir)
            throws IOException {
        return List.of(
                Charts.save(aiTopicShare(byQuarter), dir, "task1_ai_topic_share"),
                Charts.save(pushesPerDeveloper(activity), dir, "task1_pushes_per_developer"),
                Charts.save(developersNetChange(activity), dir, "task1_developers_net_change"),
                Charts.save(aiShareByCountry(byCountry, countryQuarter), dir, "task1_ai_share_by_country"));
    }

    /** Quarter as a decimal year: 2023Q1 -> 2023.0, 2025Q2 -> 2025.25. */
    static double x(Row row) {
        return AiActivity.decimalYear(AiActivity.quarterLabel(row.getInt("year"), row.getInt("quarter")));
    }

    private static XYChart aiTopicShare(Table byQuarter) {
        XYChart chart = Charts.lines("Доля ИИ-топиков среди всех топиков репозиториев GitHub", "", "% упоминаний топиков");
        double[] x = byQuarter.stream().mapToDouble(Task1Charts::x).toArray();
        String[][] groups = {
                {"classic_ml_share_pct", "классическое ML"},
                {"general_ai_share_pct", "«ai» в целом"},
                {"llm_share_pct", "LLM и их API"},
                {"agents_share_pct", "ИИ-агенты и ИИ-инструменты для кода"}};
        List<String> labels = new ArrayList<>();
        List<Double> ends = new ArrayList<>();
        double max = 0;
        for (int i = 0; i < groups.length; i++) {
            double[] y = byQuarter.doubleColumn(groups[i][0]).asDoubleArray();
            Charts.line(chart, groups[i][1], x, y, i);
            double end = y[y.length - 1];
            labels.add(percent(end));
            ends.add(end);
            for (double v : y) {
                max = Math.max(max, v);
            }
        }
        double xLast = x[x.length - 1];
        double yMax = Math.ceil(max * 1.15);
        frame(chart, x[0], xLast + RIGHT_MARGIN, yMax);
        double xPerChar = xPerChar(x[0], xLast + RIGHT_MARGIN);
        Charts.event(chart, CHATGPT_X, "ChatGPT", yMax * 0.95, xPerChar);
        Charts.event(chart, CODING_AGENTS_X, "ИИ-агенты", yMax * 0.95, xPerChar);
        Charts.endLabels(chart, xLast, labels, ends, yMax * 0.05, xPerChar);
        return chart;
    }

    private static XYChart pushesPerDeveloper(Table activity) {
        XYChart chart = Charts.lines("Пушей на одного разработчика за квартал", "", "пушей на разработчика");
        double[] x = activity.stream().mapToDouble(Task1Charts::x).toArray();
        double[] y = activity.doubleColumn("pushes_per_developer").asDoubleArray();
        Charts.line(chart, "пушей на разработчика", x, y, 0);
        chart.getStyler().setLegendVisible(false);
        double xLast = x[x.length - 1];
        double yMax = Math.max(2.0, Math.ceil(java.util.Arrays.stream(y).max().orElse(0) * 1.15 * 10) / 10);
        frame(chart, x[0], xLast + RIGHT_MARGIN, yMax);
        double xPerChar = xPerChar(x[0], xLast + RIGHT_MARGIN);
        Charts.event(chart, CHATGPT_X, "ChatGPT", yMax * 0.95, xPerChar);
        Charts.event(chart, CODING_AGENTS_X, "ИИ-агенты", yMax * 0.95, xPerChar);
        Charts.endLabels(chart, xLast, List.of(String.format(Locale.forLanguageTag("ru"), "%.2f", y[y.length - 1])),
                List.of(y[y.length - 1]), 0, xPerChar);
        return chart;
    }

    /** Net change of the developer stock (not sign-ups), see {@link AiActivity#activity}. */
    private static CategoryChart developersNetChange(Table activity) {
        CategoryChart chart = Charts.bars("Прирост числа разработчиков на GitHub за квартал", "", "млн аккаунтов");
        List<String> quarters = new ArrayList<>();
        List<Double> millions = new ArrayList<>();
        for (Row row : activity) {
            if (!activity.longColumn("developers_net_change").isMissing(row.getRowNumber())) {
                quarters.add(row.getString("quarter_label"));
                millions.add(row.getLong("developers_net_change") / 1e6);
            }
        }
        chart.addSeries("прирост числа разработчиков", quarters, millions);
        chart.getStyler().setLegendVisible(false);
        chart.getStyler().setXAxisLabelRotation(90);
        chart.getStyler().setYAxisMin(0.0);
        return chart;
    }

    private static CategoryChart aiShareByCountry(Table byCountry, String quarter) {
        CategoryChart chart = Charts.bars("Доля ИИ-топиков по странам, " + quarter, "", "% упоминаний топиков");
        List<String> names = new ArrayList<>();
        List<Double> others = new ArrayList<>();
        List<Double> russia = new ArrayList<>();
        for (Row row : byCountry) {
            boolean ru = row.getString("country_code").equals("RU");
            names.add(Charts.countryName(row.getString("country_code"), row.getString("name")));
            others.add(ru ? 0.0 : row.getDouble("ai_share_pct"));
            russia.add(ru ? row.getDouble("ai_share_pct") : 0.0);
        }
        CategorySeries rest = chart.addSeries(String.format(Locale.forLanguageTag("ru"),
                "страны с %,d+ упоминаний топиков", AiActivity.MIN_COUNTRY_TOPIC_PUSHERS), names, others);
        rest.setFillColor(Charts.SERIES[0]);
        CategorySeries ru = chart.addSeries("Россия", names, russia);
        ru.setFillColor(Charts.SERIES[1]);
        chart.getStyler().setXAxisLabelRotation(60);
        chart.getStyler().setYAxisMin(0.0);
        chart.getStyler().setLegendPosition(Styler.LegendPosition.InsideNE);
        return chart;
    }

    private static void frame(XYChart chart, double xMin, double xMax, double yMax) {
        chart.getStyler().setXAxisMin(xMin);
        chart.getStyler().setXAxisMax(xMax);
        chart.getStyler().setYAxisMin(0.0);
        chart.getStyler().setYAxisMax(yMax);
        chart.getStyler().setXAxisDecimalPattern("0");
    }

    private static double xPerChar(double xMin, double xMax) {
        return (xMax - xMin) / PLOT_WIDTH_PX * CHAR_PX;
    }

    private static String percent(double value) {
        return String.format(Locale.forLanguageTag("ru"), "%.1f%%", value);
    }
}
