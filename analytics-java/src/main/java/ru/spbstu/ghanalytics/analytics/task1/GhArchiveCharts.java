package ru.spbstu.ghanalytics.analytics.task1;

import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.style.markers.SeriesMarkers;
import ru.spbstu.ghanalytics.analytics.Charts;
import tech.tablesaw.api.Table;

import java.awt.BasicStroke;
import java.io.IOException;
import java.nio.file.Path;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/** Article figures use the calendar grid: missing and flagged months never join adjacent line segments. */
final class GhArchiveCharts {

    private GhArchiveCharts() {
    }

    static List<Path> write(Table monthly, Path dir) throws IOException {
        return List.of(
                Charts.save(chart(monthly, "Пуши в GH Archive: 2018–апрель 2025",
                        "млн PushEvent в месяц", "pushes_nonbot", "pushes_bot"), dir,
                        "task1_gharchive_pushes"),
                Charts.save(chart(monthly, "Коммиты в GH Archive (прокси): 2018–апрель 2025",
                        "млн, отсечка 100 на пуш", "commits_proxy_nonbot", "commits_proxy_bot"), dir,
                        "task1_gharchive_commits_proxy"),
                Charts.save(chart(monthly, "Создание репозиториев в GH Archive: 2018–апрель 2025",
                        "млн событий создания в месяц", "new_repos_nonbot", "new_repos_bot"), dir,
                        "task1_gharchive_new_repos"));
    }

    private static XYChart chart(Table monthly, String title, String yTitle, String nonbot, String bot) {
        XYChart chart = Charts.lines(title, "разрывы — пропуски или исключённые месяцы", yTitle);
        // A line is broken at every excluded or missing value, including isolated usable points.
        chart.getStyler().setMarkerSize(5);
        List<Double> gapsX = new ArrayList<>();
        List<Double> gapsY = new ArrayList<>();
        addSegments(chart, monthly, nonbot, "Без [bot]", 0, gapsX, gapsY);
        addSegments(chart, monthly, bot, "Суффикс [bot]", 1, gapsX, gapsY);
        if (!gapsX.isEmpty()) {
            XYSeries flagged = chart.addSeries("Исключённые значения", array(gapsX), array(gapsY));
            flagged.setXYSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Scatter);
            flagged.setMarker(SeriesMarkers.CIRCLE);
            flagged.setMarkerColor(Charts.MUTED);
        }
        double max = IntStream.range(0, monthly.rowCount())
                .mapToDouble(i -> Math.max(
                        finiteOrZero(Task1GhArchive.value(monthly, nonbot, i)),
                        finiteOrZero(Task1GhArchive.value(monthly, bot, i))))
                .max().orElse(0) / 1e6;
        double yMax = Math.max(0.01, max * 1.22);
        double xMin = x(Task1GhArchive.FIRST);
        double xMax = x(Task1GhArchive.LAST) + 1.0 / 12;
        chart.getStyler().setXAxisMin(xMin).setXAxisMax(xMax).setYAxisMin(0.0).setYAxisMax(yMax);
        chart.getStyler().setXAxisDecimalPattern("0");
        double charWidth = (xMax - xMin) * 6.5 / 840;
        Charts.event(chart, x(Task1GhArchive.COPILOT_PREVIEW), "Copilot: превью", yMax * 0.96, -charWidth);
        Charts.event(chart, x(Task1GhArchive.COPILOT_GA), "Copilot: GA", yMax * 0.86, -charWidth);
        Charts.event(chart, x(Task1GhArchive.CHATGPT), "ChatGPT", yMax * 0.96, charWidth);
        if (chart.getSeriesMap().isEmpty()) {
            throw new IllegalStateException("No published values for " + nonbot + " or " + bot);
        }
        return chart;
    }

    private static void addSegments(XYChart chart, Table monthly, String metric, String label, int slot,
                                    List<Double> gapsX, List<Double> gapsY) {
        List<Double> segmentX = new ArrayList<>();
        List<Double> segmentY = new ArrayList<>();
        int segment = 0;
        for (int i = 0; i < monthly.rowCount(); i++) {
            double date = x(Task1GhArchive.month(monthly, i));
            double y = Task1GhArchive.value(monthly, metric, i) / 1e6;
            String status = Task1GhArchive.status(monthly, i);
            if (Task1GhArchive.OBSERVED.equals(status) && Double.isFinite(y)) {
                segmentX.add(date);
                segmentY.add(y);
            } else {
                if (Task1GhArchive.GAP.equals(status) && Double.isFinite(y)) {
                    gapsX.add(date);
                    gapsY.add(y);
                }
                if (!segmentX.isEmpty()) {
                    drawSegment(chart, label, slot, segment++, segmentX, segmentY);
                    segmentX.clear();
                    segmentY.clear();
                }
            }
        }
        if (!segmentX.isEmpty()) {
            drawSegment(chart, label, slot, segment, segmentX, segmentY);
        }
    }

    private static void drawSegment(XYChart chart, String label, int slot, int segment,
                                    List<Double> x, List<Double> y) {
        XYSeries series = Charts.line(chart, segment == 0 ? label : label + " " + segment,
                array(x), array(y), slot);
        // XChart would otherwise cycle strokes for each separately added segment.
        series.setLineStyle(slot == 0 ? new BasicStroke(2f) :
                new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                        10f, new float[]{5f, 3f}, 0f));
        series.setShowInLegend(segment == 0);
        if (x.size() == 1) {
            series.setMarker(SeriesMarkers.CIRCLE);
            series.setMarkerColor(Charts.SERIES[slot]);
        }
    }

    private static double x(YearMonth month) {
        return month.getYear() + (month.getMonthValue() - 1) / 12.0;
    }

    private static double[] array(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double finiteOrZero(double v) {
        return Double.isFinite(v) ? v : 0;
    }
}
