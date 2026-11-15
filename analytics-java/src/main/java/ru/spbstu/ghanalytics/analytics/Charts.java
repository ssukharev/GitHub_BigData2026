package ru.spbstu.ghanalytics.analytics;

import org.knowm.xchart.AnnotationLine;
import org.knowm.xchart.AnnotationText;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.CategoryChart;
import org.knowm.xchart.CategoryChartBuilder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.internal.chartpart.Chart;
import org.knowm.xchart.style.AxesChartStyler;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Common look of the article figures: a validated categorical palette (fixed slot order, never cycled),
 * thin 2px lines, hairline grid, recessive axes, PNG at 200 DPI. Values stay readable without color:
 * every figure has its CSV table in results/.
 */
public final class Charts {

    /** Categorical slots in fixed order; slots 1-7 pass the colorblind checks as adjacent lines. */
    public static final Color[] SERIES = {
            new Color(0x2a78d6), new Color(0xeb6834), new Color(0x1baf7a), new Color(0xeda100),
            new Color(0xe87ba4), new Color(0x008300), new Color(0x6250d6), new Color(0xe34948)};
    /** Muted ink: data that is shown but flagged (not a series color). */
    public static final Color MUTED = new Color(0xb5b3ab);

    private static final Color SURFACE = new Color(0xfcfcfb);
    private static final Color INK = new Color(0x0b0b0b);
    private static final Color INK_SECONDARY = new Color(0x52514e);
    private static final Color AXIS_LABEL = new Color(0x898781);
    private static final Color GRID = new Color(0xe1e0d9);
    private static final Color BASELINE = new Color(0xc3c2b7);

    private static final String FONT = Font.SANS_SERIF;
    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;
    private static final int DPI = 200;

    private Charts() {
    }

    public static XYChart lines(String title, String xTitle, String yTitle) {
        XYChart chart = new XYChartBuilder().width(WIDTH).height(HEIGHT)
                .title(title).xAxisTitle(xTitle).yAxisTitle(yTitle).build();
        style(chart.getStyler());
        chart.getStyler().setMarkerSize(0);
        return chart;
    }

    public static CategoryChart bars(String title, String xTitle, String yTitle) {
        CategoryChart chart = new CategoryChartBuilder().width(WIDTH).height(HEIGHT)
                .title(title).xAxisTitle(xTitle).yAxisTitle(yTitle).build();
        style(chart.getStyler());
        chart.getStyler().setAvailableSpaceFill(0.7).setOverlapped(true);
        chart.getStyler().setPlotGridVerticalLinesVisible(false);
        return chart;
    }

    public static XYSeries line(XYChart chart, String name, double[] x, double[] y, int slot) {
        XYSeries s = chart.addSeries(name, x, y);
        s.setLineColor(SERIES[slot]);
        s.setLineWidth(2f);
        s.setMarker(SeriesMarkers.NONE);
        return s;
    }

    /** A vertical event line with its label to the right of the line, near the top of the plot. */
    public static void event(XYChart chart, double x, String label, double yTop, double xPerChar) {
        chart.addAnnotation(new AnnotationLine(x, true, false));
        chart.addAnnotation(new AnnotationText(label, x + xPerChar * (label.length() / 2.0 + 1), yTop, false));
    }

    /** A label to the right of the last point of each line, nudged apart so labels never overlap. */
    public static void endLabels(XYChart chart, double xLast, List<String> labels, List<Double> values,
                                 double minGap, double xPerChar) {
        record Label(String text, double y) {
        }
        List<Label> sorted = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) {
            sorted.add(new Label(labels.get(i), values.get(i)));
        }
        sorted.sort(Comparator.comparingDouble(Label::y));
        double previous = Double.NEGATIVE_INFINITY;
        for (Label l : sorted) {
            double y = Math.max(l.y(), previous + minGap);
            chart.addAnnotation(new AnnotationText(l.text(), xLast + xPerChar * (l.text().length() / 2.0 + 1), y, false));
            previous = y;
        }
    }

    public static Path save(Chart<?, ?> chart, Path dir, String name) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name + ".png");
        BitmapEncoder.saveBitmapWithDPI(chart, file.toString(), BitmapEncoder.BitmapFormat.PNG, DPI);
        return file;
    }

    private static final java.util.Map<String, String> COUNTRY_NAMES = java.util.Map.ofEntries(
            java.util.Map.entry("AU", "Австралия"), java.util.Map.entry("BD", "Бангладеш"),
            java.util.Map.entry("BR", "Бразилия"), java.util.Map.entry("CA", "Канада"),
            java.util.Map.entry("CH", "Швейцария"), java.util.Map.entry("CN", "Китай"),
            java.util.Map.entry("CY", "Кипр"), java.util.Map.entry("DE", "Германия"),
            java.util.Map.entry("DK", "Дания"), java.util.Map.entry("EE", "Эстония"),
            java.util.Map.entry("EG", "Египет"), java.util.Map.entry("ES", "Испания"),
            java.util.Map.entry("FI", "Финляндия"), java.util.Map.entry("FR", "Франция"),
            java.util.Map.entry("GB", "Великобритания"), java.util.Map.entry("HK", "Гонконг"),
            java.util.Map.entry("ID", "Индонезия"), java.util.Map.entry("IE", "Ирландия"),
            java.util.Map.entry("IL", "Израиль"), java.util.Map.entry("IN", "Индия"),
            java.util.Map.entry("IT", "Италия"), java.util.Map.entry("JP", "Япония"),
            java.util.Map.entry("KR", "Южная Корея"), java.util.Map.entry("LT", "Литва"),
            java.util.Map.entry("LV", "Латвия"), java.util.Map.entry("NG", "Нигерия"),
            java.util.Map.entry("NL", "Нидерланды"), java.util.Map.entry("NO", "Норвегия"),
            java.util.Map.entry("NZ", "Новая Зеландия"), java.util.Map.entry("PK", "Пакистан"),
            java.util.Map.entry("PL", "Польша"), java.util.Map.entry("PT", "Португалия"),
            java.util.Map.entry("RU", "Россия"), java.util.Map.entry("SE", "Швеция"),
            java.util.Map.entry("SG", "Сингапур"), java.util.Map.entry("TR", "Турция"),
            java.util.Map.entry("TW", "Тайвань"), java.util.Map.entry("US", "США"),
            java.util.Map.entry("VN", "Вьетнам"), java.util.Map.entry("UA", "Украина"),
            java.util.Map.entry("KZ", "Казахстан"), java.util.Map.entry("BY", "Беларусь"),
            java.util.Map.entry("AT", "Австрия"), java.util.Map.entry("BE", "Бельгия"),
            java.util.Map.entry("CZ", "Чехия"), java.util.Map.entry("SK", "Словакия"),
            java.util.Map.entry("SI", "Словения"), java.util.Map.entry("HU", "Венгрия"),
            java.util.Map.entry("RO", "Румыния"), java.util.Map.entry("BG", "Болгария"),
            java.util.Map.entry("GR", "Греция"), java.util.Map.entry("HR", "Хорватия"),
            java.util.Map.entry("LU", "Люксембург"), java.util.Map.entry("IS", "Исландия"),
            java.util.Map.entry("RS", "Сербия"), java.util.Map.entry("AM", "Армения"),
            java.util.Map.entry("GE", "Грузия"), java.util.Map.entry("UY", "Уругвай"),
            java.util.Map.entry("CL", "Чили"), java.util.Map.entry("AR", "Аргентина"));

    /** Russian name for the countries that appear on the figures; the World Bank name otherwise. */
    public static String countryName(String code, String fallback) {
        return COUNTRY_NAMES.getOrDefault(code, fallback);
    }

    private static void style(AxesChartStyler s) {
        s.setChartBackgroundColor(SURFACE);
        s.setPlotBackgroundColor(SURFACE);
        s.setPlotBorderVisible(false);
        s.setPlotGridLinesColor(GRID);
        s.setPlotGridLinesStroke(new BasicStroke(1f));
        s.setPlotGridVerticalLinesVisible(false);
        s.setAxisTicksMarksVisible(false);
        s.setAxisTickMarksColor(BASELINE);
        s.setAxisTickLabelsColor(AXIS_LABEL);
        s.setAxisTickLabelsFont(new Font(FONT, Font.PLAIN, 12));
        s.setAxisTitleFont(new Font(FONT, Font.PLAIN, 13));
        s.setChartFontColor(INK_SECONDARY);
        s.setChartTitleFont(new Font(FONT, Font.BOLD, 15));
        s.setChartTitleBoxVisible(false);
        s.setChartPadding(16);
        s.setLegendFont(new Font(FONT, Font.PLAIN, 12));
        s.setLegendBackgroundColor(SURFACE);
        s.setLegendBorderColor(SURFACE);
        s.setLegendPosition(Styler.LegendPosition.OutsideS);
        s.setLegendLayout(Styler.LegendLayout.Horizontal);
        s.setSeriesColors(SERIES);
        s.setAnnotationLineColor(BASELINE);
        s.setAnnotationLineStroke(new BasicStroke(1f));
        s.setAnnotationTextFont(new Font(FONT, Font.PLAIN, 12));
        s.setAnnotationTextFontColor(INK);
        s.setLocale(Locale.forLanguageTag("ru"));
        s.setAntiAlias(true);
        s.setToolTipsEnabled(false);
    }
}
