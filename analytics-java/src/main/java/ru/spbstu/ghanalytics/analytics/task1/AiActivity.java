package ru.spbstu.ghanalytics.analytics.task1;

import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.IntColumn;
import tech.tablesaw.api.LongColumn;
import tech.tablesaw.api.Row;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Calculations of task 1 on the rows of {@code sql/task1/*.sql}. */
public final class AiActivity {

    /** Countries with fewer topic mentions are left out of the by-country table: their shares jump around. */
    public static final long MIN_COUNTRY_TOPIC_PUSHERS = 10_000;

    private AiActivity() {
    }

    public static String quarterLabel(int year, int quarter) {
        return year + "Q" + quarter;
    }

    /** {@code "2023Q1"} -> 2023.0, {@code "2025Q2"} -> 2025.25: the x position of a quarter on the charts. */
    public static double decimalYear(String quarterLabel) {
        int q = quarterLabel.indexOf('Q');
        return Integer.parseInt(quarterLabel.substring(0, q)) + (Integer.parseInt(quarterLabel.substring(q + 1)) - 1) / 4.0;
    }

    /**
     * Adds to {@code activity_by_quarter.sql} rows: quarter label, net change of developers since the previous
     * quarter, pushes per developer (only countries with published pushes) and repositories per developer.
     * <p>
     * Innovation Graph reports the stock of developer accounts at the end of each quarter, so the change is net:
     * new accounts minus accounts removed, flagged as spam or moved to an unpublished country, not sign-ups.
     */
    public static Table activity(Table raw) {
        Table t = raw.sortAscendingOn("year", "quarter").copy().setName("activity_by_quarter");
        StringColumn label = StringColumn.create("quarter_label");
        LongColumn netChange = LongColumn.create("developers_net_change");
        DoubleColumn pushesPerDeveloper = DoubleColumn.create("pushes_per_developer");
        DoubleColumn reposPerDeveloper = DoubleColumn.create("repositories_per_developer");
        long previous = -1;
        for (Row row : t) {
            long developers = row.getLong("developers");
            label.append(quarterLabel(row.getInt("year"), row.getInt("quarter")));
            if (previous < 0) {
                netChange.appendMissing();
            } else {
                netChange.append(developers - previous);
            }
            previous = developers;
            pushesPerDeveloper.append((double) row.getLong("git_pushes") / row.getLong("developers_with_pushes"));
            reposPerDeveloper.append((double) row.getLong("repositories") / developers);
        }
        return t.insertColumn(0, label).addColumns(netChange, pushesPerDeveloper, reposPerDeveloper);
    }

    /**
     * Share of AI topics among thematic topic mentions per quarter, overall and by {@link AiTopics.Group}, in percent.
     * Non-thematic topics ({@link AiTopics#isNonThematic}) are left out of the denominator and reported separately.
     * Input: {@code topics_by_quarter.sql} rows (year, quarter, topic, pushers).
     */
    public static Table aiShareByQuarter(Table topics) {
        Map<String, long[]> totals = new TreeMap<>();   // thematic pushers, non-thematic pushers, thematic topics
        Map<String, Map<AiTopics.Group, Long>> aiByQuarter = new TreeMap<>();
        Map<String, int[]> yearQuarter = new LinkedHashMap<>();
        for (Row row : topics) {
            String q = quarterLabel(row.getInt("year"), row.getInt("quarter"));
            yearQuarter.putIfAbsent(q, new int[]{row.getInt("year"), row.getInt("quarter")});
            long[] t = totals.computeIfAbsent(q, k -> new long[3]);
            Map<AiTopics.Group, Long> ai = aiByQuarter.computeIfAbsent(q, k -> new EnumMap<>(AiTopics.Group.class));
            String topic = row.getString("topic");
            long pushers = row.getLong("pushers");
            if (AiTopics.isNonThematic(topic)) {
                t[1] += pushers;
                continue;
            }
            t[0] += pushers;
            t[2]++;
            AiTopics.group(topic).ifPresent(g -> ai.merge(g, pushers, Long::sum));
        }

        StringColumn label = StringColumn.create("quarter_label");
        IntColumn year = IntColumn.create("year");
        IntColumn quarter = IntColumn.create("quarter");
        IntColumn topicCount = IntColumn.create("topics");
        LongColumn all = LongColumn.create("all_topic_pushers");
        LongColumn nonThematic = LongColumn.create("non_thematic_pushers");
        LongColumn ai = LongColumn.create("ai_topic_pushers");
        DoubleColumn aiShare = DoubleColumn.create("ai_share_pct");
        Map<AiTopics.Group, DoubleColumn> groupShare = new EnumMap<>(AiTopics.Group.class);
        for (AiTopics.Group g : AiTopics.Group.values()) {
            groupShare.put(g, DoubleColumn.create(g.column() + "_share_pct"));
        }
        totals.forEach((q, t) -> {
            Map<AiTopics.Group, Long> groups = aiByQuarter.get(q);
            long aiSum = groups.values().stream().mapToLong(Long::longValue).sum();
            label.append(q);
            year.append(yearQuarter.get(q)[0]);
            quarter.append(yearQuarter.get(q)[1]);
            topicCount.append((int) t[2]);
            all.append(t[0]);
            nonThematic.append(t[1]);
            ai.append(aiSum);
            aiShare.append(100.0 * aiSum / t[0]);
            for (AiTopics.Group g : AiTopics.Group.values()) {
                groupShare.get(g).append(100.0 * groups.getOrDefault(g, 0L) / t[0]);
            }
        });
        Table result = Table.create("ai_topics_by_quarter", label, year, quarter, topicCount, all, nonThematic, ai,
                aiShare);
        groupShare.values().forEach(result::addColumns);
        return result;
    }

    /**
     * Adds {@code ai_per_1000_developers}: AI topic mentions per 1000 developer accounts of the same quarter. Unlike
     * the share it does not depend on the other topics at all, so it checks that the share is not a denominator
     * artefact. Input: {@link #aiShareByQuarter} and {@link #activity} tables.
     */
    public static Table withAiPerThousandDevelopers(Table byQuarter, Table activity) {
        Map<String, Long> developers = new TreeMap<>();
        for (Row row : activity) {
            developers.put(row.getString("quarter_label"), row.getLong("developers"));
        }
        DoubleColumn perThousand = DoubleColumn.create("ai_per_1000_developers");
        for (Row row : byQuarter) {
            Long d = developers.get(row.getString("quarter_label"));
            if (d == null || d == 0) {
                perThousand.appendMissing();
            } else {
                perThousand.append(1000.0 * row.getLong("ai_topic_pushers") / d);
            }
        }
        return byQuarter.addColumns(perThousand);
    }

    /** By-country shares and the topic set they are computed on. */
    public record CountryShares(Table table, List<String> commonTopics) {
    }

    /**
     * Share of AI topics by country in one quarter, highest first. Innovation Graph publishes a topic for a country
     * only when at least 100 of its developers used it, so countries get very different topic lists (1701 topics for
     * the US, 88 for Russia in 2026Q2) and shares over each country's own list are not comparable. The share is
     * therefore computed on the topics published for every compared country; the share over the country's own
     * topics is kept for reference. Countries need at least {@code minTopicPushers} thematic topic mentions.
     * Input: {@code topics_by_country.sql} rows.
     */
    public static CountryShares aiShareByCountry(Table topics, long minTopicPushers) {
        Map<String, String> names = new TreeMap<>();
        Map<String, Map<String, Long>> byCountry = new TreeMap<>();
        for (Row row : topics) {
            String topic = row.getString("topic");
            if (AiTopics.isNonThematic(topic)) {
                continue;
            }
            String code = row.getString("country_code");
            names.putIfAbsent(code, row.getString("name"));
            byCountry.computeIfAbsent(code, k -> new TreeMap<>()).merge(topic, row.getLong("pushers"), Long::sum);
        }
        byCountry.values().removeIf(t -> t.values().stream().mapToLong(Long::longValue).sum() < minTopicPushers);

        Set<String> common = null;
        for (Map<String, Long> t : byCountry.values()) {
            if (common == null) {
                common = new TreeSet<>(t.keySet());
            } else {
                common.retainAll(t.keySet());
            }
        }
        List<String> commonTopics = common == null ? List.of() : List.copyOf(common);

        StringColumn code = StringColumn.create("country_code");
        StringColumn name = StringColumn.create("name");
        IntColumn published = IntColumn.create("topics_published");
        LongColumn commonPushers = LongColumn.create("common_topic_pushers");
        LongColumn aiCommon = LongColumn.create("ai_common_topic_pushers");
        DoubleColumn share = DoubleColumn.create("ai_share_pct");
        DoubleColumn shareOwn = DoubleColumn.create("ai_share_own_topics_pct");
        byCountry.forEach((c, t) -> {
            long all = 0;
            long ai = 0;
            long inCommon = 0;
            long aiInCommon = 0;
            for (Map.Entry<String, Long> e : t.entrySet()) {
                boolean isAi = AiTopics.group(e.getKey()).isPresent();
                all += e.getValue();
                ai += isAi ? e.getValue() : 0;
                if (commonTopics.contains(e.getKey())) {
                    inCommon += e.getValue();
                    aiInCommon += isAi ? e.getValue() : 0;
                }
            }
            code.append(c);
            name.append(names.get(c));
            published.append(t.size());
            commonPushers.append(inCommon);
            aiCommon.append(aiInCommon);
            share.append(inCommon == 0 ? Double.NaN : 100.0 * aiInCommon / inCommon);
            shareOwn.append(100.0 * ai / all);
        });
        Table table = Table.create("ai_topics_by_country", code, name, published, commonPushers, aiCommon, share,
                shareOwn).sortDescendingOn("ai_share_pct");
        return new CountryShares(table, commonTopics);
    }

    /** One fitted series around one event; {@code placebo} is null until {@link #withPlacebo} is applied. */
    public record EventFit(String series, String event, String window, String firstQuarterAfter,
                           SegmentedRegression.Fit fit, Placebo placebo) {

        public EventFit withPlacebo(Placebo p) {
            return new EventFit(series, event, window, firstQuarterAfter, fit, p);
        }
    }

    /**
     * The same regression with the break moved to every other quarter of the window (at least {@code minSide}
     * points on each side). {@code shareLevel} / {@code shareSlope}: the fraction of these placebo dates whose
     * p-value is at least as small as the real event's. A real effect should stand out: a small fraction.
     */
    public record Placebo(int dates, double shareLevel, double shareSlope, List<EventFit> fits) {
    }

    /** Quarters and values of {@code column} on {@code from..to}; rows with a missing value are skipped. */
    private record Window(List<String> quarters, double[] y) {
    }

    private static Window window(Table table, String column, String from, String to) {
        List<String> quarters = new ArrayList<>();
        List<Double> values = new ArrayList<>();
        boolean inside = false;
        for (Row row : table) {
            String q = row.getString("quarter_label");
            if (q.equals(from)) {
                inside = true;
            }
            if (inside && !table.column(column).isMissing(row.getRowNumber())) {
                quarters.add(q);
                values.add(((Number) table.column(column).get(row.getRowNumber())).doubleValue());
            }
            if (q.equals(to)) {
                break;
            }
        }
        return new Window(quarters, values.stream().mapToDouble(Double::doubleValue).toArray());
    }

    /**
     * Segmented regression of {@code column} of {@code table} (rows ordered by quarter, with a quarter_label
     * column) on the quarters {@code from..to}, with the event starting at {@code firstAfter}. Rows with a missing
     * value are skipped.
     */
    public static EventFit eventFit(Table table, String column, String event, String from, String to,
                                    String firstAfter) {
        Window w = window(table, column, from, to);
        int breakIndex = w.quarters().indexOf(firstAfter);
        if (breakIndex < 0) {
            throw new IllegalArgumentException(firstAfter + " is not inside " + from + ".." + to);
        }
        return new EventFit(column, event, from + ".." + to, firstAfter, SegmentedRegression.fit(w.y(), breakIndex),
                null);
    }

    /** Placebo check of {@code real}: the same series, window and model with the break at every other quarter. */
    public static Placebo placebo(Table table, EventFit real, int minSide) {
        String[] bounds = real.window().split("\\.\\.");
        Window w = window(table, real.series(), bounds[0], bounds[1]);
        List<EventFit> fits = new ArrayList<>();
        int asSmallLevel = 0;
        int asSmallSlope = 0;
        for (int k = Math.max(minSide, 2); k <= w.y().length - Math.max(minSide, 2); k++) {
            String q = w.quarters().get(k);
            if (q.equals(real.firstQuarterAfter())) {
                continue;
            }
            SegmentedRegression.Fit f = SegmentedRegression.fit(w.y(), k);
            fits.add(new EventFit(real.series(), "placebo:" + real.event(), real.window(), q, f, null));
            asSmallLevel += f.pLevel() <= real.fit().pLevel() ? 1 : 0;
            asSmallSlope += f.pSlope() <= real.fit().pSlope() ? 1 : 0;
        }
        int n = fits.size();
        return new Placebo(n, n == 0 ? Double.NaN : (double) asSmallLevel / n,
                n == 0 ? Double.NaN : (double) asSmallSlope / n, fits);
    }

    public static Table eventFits(List<EventFit> fits) {
        StringColumn series = StringColumn.create("series");
        StringColumn event = StringColumn.create("event");
        StringColumn window = StringColumn.create("window");
        StringColumn after = StringColumn.create("first_quarter_after");
        IntColumn n = IntColumn.create("n");
        DoubleColumn slopeBefore = DoubleColumn.create("slope_before");
        DoubleColumn slopeAfter = DoubleColumn.create("slope_after");
        DoubleColumn level = DoubleColumn.create("level_change");
        DoubleColumn pLevel = DoubleColumn.create("p_level");
        DoubleColumn slope = DoubleColumn.create("slope_change");
        DoubleColumn pSlope = DoubleColumn.create("p_slope");
        DoubleColumn r2 = DoubleColumn.create("r_squared");
        DoubleColumn dw = DoubleColumn.create("durbin_watson");
        IntColumn placeboDates = IntColumn.create("placebo_dates");
        DoubleColumn placeboLevel = DoubleColumn.create("placebo_share_p_level");
        DoubleColumn placeboSlope = DoubleColumn.create("placebo_share_p_slope");
        for (EventFit f : fits) {
            series.append(f.series());
            event.append(f.event());
            window.append(f.window());
            after.append(f.firstQuarterAfter());
            n.append(f.fit().n());
            slopeBefore.append(f.fit().slopeBefore());
            slopeAfter.append(f.fit().slopeAfter());
            level.append(f.fit().levelChange());
            pLevel.append(f.fit().pLevel());
            slope.append(f.fit().slopeChange());
            pSlope.append(f.fit().pSlope());
            r2.append(f.fit().rSquared());
            dw.append(f.fit().durbinWatson());
            if (f.placebo() == null) {
                placeboDates.appendMissing();
                placeboLevel.appendMissing();
                placeboSlope.appendMissing();
            } else {
                placeboDates.append(f.placebo().dates());
                placeboLevel.append(f.placebo().shareLevel());
                placeboSlope.append(f.placebo().shareSlope());
            }
        }
        return Table.create("event_regressions", series, event, window, after, n,
                slopeBefore, slopeAfter, level, pLevel, slope, pSlope, r2, dw, placeboDates,
                placeboLevel, placeboSlope);
    }
}
