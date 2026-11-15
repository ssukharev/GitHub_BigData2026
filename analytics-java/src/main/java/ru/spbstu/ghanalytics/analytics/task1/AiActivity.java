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
import java.util.TreeMap;

/** Calculations of task 1 on the rows of {@code sql/task1/*.sql}. */
public final class AiActivity {

    /** Countries with fewer topic mentions are left out of the by-country table: their shares jump around. */
    public static final long MIN_COUNTRY_TOPIC_PUSHERS = 10_000;

    private AiActivity() {
    }

    public static String quarterLabel(int year, int quarter) {
        return year + "Q" + quarter;
    }

    /**
     * Adds to {@code activity_by_quarter.sql} rows: quarter label, new developers since the previous quarter,
     * pushes per developer (only countries with published pushes) and repositories per developer.
     */
    public static Table activity(Table raw) {
        Table t = raw.sortAscendingOn("year", "quarter").copy().setName("activity_by_quarter");
        StringColumn label = StringColumn.create("quarter_label");
        LongColumn newDevelopers = LongColumn.create("new_developers");
        DoubleColumn pushesPerDeveloper = DoubleColumn.create("pushes_per_developer");
        DoubleColumn reposPerDeveloper = DoubleColumn.create("repositories_per_developer");
        long previous = -1;
        for (Row row : t) {
            long developers = row.getLong("developers");
            label.append(quarterLabel(row.getInt("year"), row.getInt("quarter")));
            if (previous < 0) {
                newDevelopers.appendMissing();
            } else {
                newDevelopers.append(developers - previous);
            }
            previous = developers;
            pushesPerDeveloper.append((double) row.getLong("git_pushes") / row.getLong("developers_with_pushes"));
            reposPerDeveloper.append((double) row.getLong("repositories") / developers);
        }
        return t.insertColumn(0, label).addColumns(newDevelopers, pushesPerDeveloper, reposPerDeveloper);
    }

    /**
     * Share of AI topics among all topic mentions per quarter, overall and by {@link AiTopics.Group}, in percent.
     * Input: {@code topics_by_quarter.sql} rows (year, quarter, topic, pushers).
     */
    public static Table aiShareByQuarter(Table topics) {
        record Totals(long all, int topicCount, Map<AiTopics.Group, Long> ai) {
        }
        Map<String, Totals> byQuarter = new TreeMap<>();
        Map<String, int[]> yearQuarter = new LinkedHashMap<>();
        for (Row row : topics) {
            String q = quarterLabel(row.getInt("year"), row.getInt("quarter"));
            yearQuarter.putIfAbsent(q, new int[]{row.getInt("year"), row.getInt("quarter")});
            Totals totals = byQuarter.computeIfAbsent(q, k -> new Totals(0, 0, new EnumMap<>(AiTopics.Group.class)));
            long pushers = row.getLong("pushers");
            AiTopics.group(row.getString("topic")).ifPresent(g -> totals.ai().merge(g, pushers, Long::sum));
            byQuarter.put(q, new Totals(totals.all() + pushers, totals.topicCount() + 1, totals.ai()));
        }

        StringColumn label = StringColumn.create("quarter_label");
        IntColumn year = IntColumn.create("year");
        IntColumn quarter = IntColumn.create("quarter");
        IntColumn topicCount = IntColumn.create("topics");
        LongColumn all = LongColumn.create("all_topic_pushers");
        LongColumn ai = LongColumn.create("ai_topic_pushers");
        DoubleColumn aiShare = DoubleColumn.create("ai_share_pct");
        Map<AiTopics.Group, DoubleColumn> groupShare = new EnumMap<>(AiTopics.Group.class);
        for (AiTopics.Group g : AiTopics.Group.values()) {
            groupShare.put(g, DoubleColumn.create(g.column() + "_share_pct"));
        }
        byQuarter.forEach((q, totals) -> {
            label.append(q);
            year.append(yearQuarter.get(q)[0]);
            quarter.append(yearQuarter.get(q)[1]);
            topicCount.append(totals.topicCount());
            all.append(totals.all());
            long aiSum = totals.ai().values().stream().mapToLong(Long::longValue).sum();
            ai.append(aiSum);
            aiShare.append(100.0 * aiSum / totals.all());
            for (AiTopics.Group g : AiTopics.Group.values()) {
                groupShare.get(g).append(100.0 * totals.ai().getOrDefault(g, 0L) / totals.all());
            }
        });
        Table result = Table.create("ai_topics_by_quarter", label, year, quarter, topicCount, all, ai, aiShare);
        groupShare.values().forEach(result::addColumns);
        return result;
    }

    /**
     * Share of AI topics by country in one quarter, for countries with at least {@code minTopicPushers} topic
     * mentions, highest share first. Input: {@code topics_by_country.sql} rows.
     */
    public static Table aiShareByCountry(Table topics, long minTopicPushers) {
        Map<String, String> names = new TreeMap<>();
        Map<String, long[]> sums = new TreeMap<>();
        for (Row row : topics) {
            String code = row.getString("country_code");
            names.putIfAbsent(code, row.getString("name"));
            long[] s = sums.computeIfAbsent(code, k -> new long[2]);
            s[0] += row.getLong("pushers");
            if (AiTopics.group(row.getString("topic")).isPresent()) {
                s[1] += row.getLong("pushers");
            }
        }
        StringColumn code = StringColumn.create("country_code");
        StringColumn name = StringColumn.create("name");
        LongColumn all = LongColumn.create("all_topic_pushers");
        LongColumn ai = LongColumn.create("ai_topic_pushers");
        DoubleColumn share = DoubleColumn.create("ai_share_pct");
        sums.forEach((c, s) -> {
            if (s[0] >= minTopicPushers) {
                code.append(c);
                name.append(names.get(c));
                all.append(s[0]);
                ai.append(s[1]);
                share.append(100.0 * s[1] / s[0]);
            }
        });
        return Table.create("ai_topics_by_country", code, name, all, ai, share).sortDescendingOn("ai_share_pct");
    }

    /** One fitted series around one event. */
    public record EventFit(String series, String event, String window, String firstQuarterAfter,
                           SegmentedRegression.Fit fit) {
    }

    /**
     * Segmented regression of {@code column} of {@code table} (rows ordered by quarter, with a quarter_label
     * column) on the quarters {@code from..to}, with the event starting at {@code firstAfter}. Rows with a missing
     * value are skipped.
     */
    public static EventFit eventFit(Table table, String column, String event, String from, String to,
                                    String firstAfter) {
        List<Double> values = new ArrayList<>();
        int breakIndex = -1;
        boolean inside = false;
        for (Row row : table) {
            String q = row.getString("quarter_label");
            if (q.equals(from)) {
                inside = true;
            }
            if (inside && !table.column(column).isMissing(row.getRowNumber())) {
                if (q.equals(firstAfter)) {
                    breakIndex = values.size();
                }
                values.add(((Number) table.column(column).get(row.getRowNumber())).doubleValue());
            }
            if (q.equals(to)) {
                break;
            }
        }
        if (breakIndex < 0) {
            throw new IllegalArgumentException(firstAfter + " is not inside " + from + ".." + to);
        }
        double[] y = values.stream().mapToDouble(Double::doubleValue).toArray();
        return new EventFit(column, event, from + ".." + to, firstAfter, SegmentedRegression.fit(y, breakIndex));
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
        }
        return Table.create("event_regressions", series, event, window, after, n,
                slopeBefore, slopeAfter, level, pLevel, slope, pSlope, r2);
    }
}
