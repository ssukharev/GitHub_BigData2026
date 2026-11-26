package ru.spbstu.ghanalytics.analytics.task1;

import ru.spbstu.ghanalytics.analytics.DbConfig;
import ru.spbstu.ghanalytics.analytics.Sql;
import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.IntColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** GH Archive extension of task 1, independent of the Innovation Graph and World Bank analyses. */
public final class Task1GhArchive {

    static final YearMonth FIRST = YearMonth.of(2018, 1);
    static final YearMonth LAST = YearMonth.of(2025, 4);
    static final YearMonth COPILOT_PREVIEW = YearMonth.of(2021, 7);
    static final YearMonth COPILOT_GA = YearMonth.of(2022, 7);
    static final YearMonth CHATGPT = YearMonth.of(2022, 12);

    static final List<String> METRICS = List.of(
            "pushes_nonbot", "pushes_bot", "push_actors_nonbot", "push_actors_bot",
            "push_repos_nonbot", "push_repos_bot",
            "commits_proxy_nonbot", "commits_proxy_bot", "new_repos_nonbot", "new_repos_bot",
            "bot_push_share_pct");
    static final String SHARE = "bot_push_share_pct";
    static final String OBSERVED = "observed";
    static final String GAP = "known_gap";
    static final String MISSING = "missing_month";

    record Window(String name, YearMonth afterStart, int months) {
        YearMonth beforeStart() {
            return afterStart.minusYears(1);
        }

        YearMonth beforeEnd() {
            return beforeStart().plusMonths(months - 1L);
        }

        YearMonth afterEnd() {
            return afterStart.plusMonths(months - 1L);
        }
    }

    record Comparison(int matchedPairs, int gapPairs, int missingPairs,
                      double meanBefore, double meanAfter) {
        double change() {
            return meanAfter - meanBefore;
        }

        double relativeChangePct() {
            return meanBefore == 0 ? Double.NaN : 100.0 * change() / meanBefore;
        }
    }

    private Task1GhArchive() {
    }

    public static void run(DbConfig db, Path out, Path figures) throws SQLException, IOException {
        Table monthly;
        try (Connection conn = db.open()) {
            conn.setReadOnly(true);
            monthly = Sql.table(conn, "task1/gharchive_monthly");
        }
        if (monthly.stringColumn("status").asList().stream().allMatch(MISSING::equals)) {
            throw new IllegalStateException("No comparable GH Archive data: restore data-gharchive-2026-11-17 first");
        }
        Table comparisons = comparisons(monthly);
        Files.createDirectories(out);
        monthly.write().csv(out.resolve("monthly_activity.csv").toFile());
        comparisons.write().csv(out.resolve("matched_comparisons.csv").toFile());
        List<Path> charts = GhArchiveCharts.write(monthly, figures);
        long gap = monthly.stringColumn("status").asList().stream().filter(GAP::equals).count();
        long missing = monthly.stringColumn("status").asList().stream().filter(MISSING::equals).count();
        System.out.printf("GH Archive: %s..%s, %d months, %d flagged, %d absent; CSVs: %s%n",
                FIRST, LAST, monthly.rowCount(), gap, missing, out.toAbsolutePath());
        charts.forEach(c -> System.out.println("figure: " + c.toAbsolutePath().normalize()));
    }

    static List<Window> windows() {
        List<Window> result = new ArrayList<>(List.of(
                new Window("copilot_preview", COPILOT_PREVIEW, 12),
                new Window("copilot_ga", COPILOT_GA, 12),
                new Window("chatgpt", CHATGPT, 12)));
        for (int year = 2019; year <= 2025; year++) {
            result.add(new Window("jan_apr_" + year, YearMonth.of(year, 1), 4));
        }
        return List.copyOf(result);
    }

    static YearMonth month(Table monthly, int i) {
        return YearMonth.from(monthly.dateColumn("month").get(i));
    }

    static double value(Table monthly, String metric, int i) {
        return monthly.column(metric).isMissing(i) ? Double.NaN : monthly.numberColumn(metric).getDouble(i);
    }

    static String status(Table monthly, int i) {
        return monthly.stringColumn("status").get(i);
    }

    /** Pair calendar months exactly 12 months apart. Never compress time across gaps. */
    static Comparison compare(Table monthly, Map<YearMonth, Integer> index, Window window, String metric) {
        int matched = 0;
        int gapPairs = 0;
        int missingPairs = 0;
        double sumBefore = 0;
        double sumAfter = 0;
        for (int i = 0; i < window.months(); i++) {
            YearMonth afterMonth = window.afterStart().plusMonths(i);
            Integer before = index.get(afterMonth.minusYears(1));
            Integer after = index.get(afterMonth);
            if (before == null || after == null || MISSING.equals(status(monthly, before))
                    || MISSING.equals(status(monthly, after))) {
                missingPairs++;
                continue;
            }
            if (GAP.equals(status(monthly, before)) || GAP.equals(status(monthly, after))) {
                gapPairs++;
                continue;
            }
            double b = value(monthly, metric, before);
            double a = value(monthly, metric, after);
            if (Double.isNaN(b) || Double.isNaN(a)) {
                missingPairs++;
                continue;
            }
            sumBefore += b;
            sumAfter += a;
            matched++;
        }
        return new Comparison(matched, gapPairs, missingPairs,
                matched == 0 ? Double.NaN : sumBefore / matched,
                matched == 0 ? Double.NaN : sumAfter / matched);
    }

    static Table comparisons(Table monthly) {
        Map<YearMonth, Integer> index = new HashMap<>();
        for (int i = 0; i < monthly.rowCount(); i++) {
            if (index.put(month(monthly, i), i) != null) {
                throw new IllegalStateException("Duplicate calendar month: " + month(monthly, i));
            }
        }
        StringColumn comparison = StringColumn.create("comparison");
        StringColumn metric = StringColumn.create("metric");
        StringColumn beforeFrom = StringColumn.create("before_from");
        StringColumn beforeTo = StringColumn.create("before_to");
        StringColumn afterFrom = StringColumn.create("after_from");
        StringColumn afterTo = StringColumn.create("after_to");
        IntColumn scheduled = IntColumn.create("scheduled_pairs");
        IntColumn matched = IntColumn.create("matched_pairs");
        IntColumn gaps = IntColumn.create("excluded_gap_pairs");
        IntColumn missing = IntColumn.create("excluded_missing_pairs");
        DoubleColumn meanBefore = DoubleColumn.create("mean_before");
        DoubleColumn meanAfter = DoubleColumn.create("mean_after");
        DoubleColumn change = DoubleColumn.create("change_absolute");
        DoubleColumn growth = DoubleColumn.create("relative_change_pct");
        for (Window window : windows()) {
            for (String series : METRICS) {
                Comparison c = compare(monthly, index, window, series);
                comparison.append(window.name());
                metric.append(series);
                beforeFrom.append(window.beforeStart().toString());
                beforeTo.append(window.beforeEnd().toString());
                afterFrom.append(window.afterStart().toString());
                afterTo.append(window.afterEnd().toString());
                scheduled.append(window.months());
                matched.append(c.matchedPairs());
                gaps.append(c.gapPairs());
                missing.append(c.missingPairs());
                meanBefore.append(c.meanBefore());
                meanAfter.append(c.meanAfter());
                change.append(c.change());
                // For shares the absolute change is already percentage points, not relative growth.
                growth.append(SHARE.equals(series) ? Double.NaN : c.relativeChangePct());
            }
        }
        return Table.create("matched_comparisons", comparison, metric, beforeFrom, beforeTo,
                afterFrom, afterTo, scheduled, matched, gaps, missing, meanBefore, meanAfter, change, growth);
    }
}
