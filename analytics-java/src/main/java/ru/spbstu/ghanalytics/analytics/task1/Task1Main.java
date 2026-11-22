package ru.spbstu.ghanalytics.analytics.task1;

import ru.spbstu.ghanalytics.analytics.DbConfig;
import ru.spbstu.ghanalytics.analytics.Sql;
import tech.tablesaw.api.LongColumn;
import tech.tablesaw.api.Row;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Task 1 tables, written as CSV to {@code out}, and figures (PNG) to {@code figures}.
 * Started from {@link ru.spbstu.ghanalytics.analytics.Main}.
 */
public final class Task1Main {

    /**
     * GitHub Copilot technical preview opened on 2021-06-29: 2021Q3 is the first full quarter after it. General
     * availability (2022-06-21, first full quarter 2022Q3) is only two quarters before ChatGPT, too close to tell the
     * two apart, so it is marked on the charts but not tested separately.
     */
    static final String COPILOT_PREVIEW = "2021Q3";
    static final String COPILOT_GA = "2022Q3";
    /** ChatGPT came out on 2022-11-30: 2023Q1 is the first full quarter after it. */
    static final String CHATGPT = "2023Q1";
    /** Agent tools for code went public in Feb-May 2025 (Copilot agent mode, Claude Code, Codex): 2025Q2. */
    static final String CODING_AGENTS = "2025Q2";
    /** The Copilot model stops before ChatGPT. */
    static final String COPILOT_WINDOW_END = "2022Q4";
    /** The ChatGPT model ends before the agent wave; the agent model starts at ChatGPT. */
    static final String CHATGPT_WINDOW_END = "2024Q4";
    /** Placebo breaks keep at least this many quarters on each side. */
    static final int PLACEBO_MIN_SIDE = 3;

    private Task1Main() {
    }

    public static void run(DbConfig db, Path out, Path figures) throws SQLException, IOException {
        Table activity;
        Table topicsByQuarter;
        AiActivity.CountryShares byCountry;
        String countryQuarter;
        try (Connection conn = db.open()) {
            Sql.requireWorldBank(conn);
            activity = AiActivity.activity(Sql.table(conn, "task1/activity_by_quarter"));
            topicsByQuarter = Sql.table(conn, "task1/topics_by_quarter");
            Table topicsByCountry = Sql.table(conn, "task1/topics_by_country");
            byCountry = AiActivity.aiShareByCountry(topicsByCountry, AiActivity.MIN_COUNTRY_TOPIC_PUSHERS);
            // label from the topics themselves: their latest quarter may differ from the stats' one
            countryQuarter = topicsByCountry.isEmpty() ? "none" : AiActivity.quarterLabel(
                    topicsByCountry.intColumn("year").get(0), topicsByCountry.intColumn("quarter").get(0));
        }
        if (activity.isEmpty() || topicsByQuarter.isEmpty()) {
            throw new IllegalStateException("No data: load collector/innovation-graph first");
        }
        Table byQuarter = AiActivity.withAiPerThousandDevelopers(AiActivity.aiShareByQuarter(topicsByQuarter), activity);
        String first = activity.stringColumn("quarter_label").get(0);
        String last = activity.stringColumn("quarter_label").get(activity.rowCount() - 1);

        List<AiActivity.EventFit> fits = new ArrayList<>();
        for (String series : List.of("ai_share_pct", "ai_per_1000_developers")) {
            fits.addAll(threeEvents(byQuarter, series, first, last));
        }
        fits.add(AiActivity.eventFit(byQuarter, "llm_share_pct", "chatgpt", first, CHATGPT_WINDOW_END, CHATGPT));
        fits.add(AiActivity.eventFit(byQuarter, "agents_share_pct", "coding_agents", CHATGPT, last, CODING_AGENTS));
        int topicFits = fits.size();
        for (String series : List.of("pushes_per_developer", "developers_net_change", "repositories_per_developer")) {
            fits.addAll(threeEvents(activity, series, first, last));
        }

        List<AiActivity.EventFit> withPlacebo = new ArrayList<>();
        List<AiActivity.EventFit> placeboFits = new ArrayList<>();
        for (int i = 0; i < fits.size(); i++) {
            AiActivity.EventFit f = fits.get(i);
            AiActivity.Placebo p = AiActivity.placebo(i < topicFits ? byQuarter : activity, f, PLACEBO_MIN_SIDE);
            withPlacebo.add(f.withPlacebo(p));
            placeboFits.addAll(p.fits());
        }
        Table regressions = AiActivity.eventFits(withPlacebo);

        Files.createDirectories(out);
        activity.write().csv(out.resolve("activity_by_quarter.csv").toFile());
        byQuarter.write().csv(out.resolve("ai_topics_by_quarter.csv").toFile());
        Table countries = byCountry.table().copy();
        StringColumn quarterColumn = StringColumn.create("quarter");
        for (int i = 0; i < countries.rowCount(); i++) {
            quarterColumn.append(countryQuarter);
        }
        countries.insertColumn(0, quarterColumn);
        countries.write().csv(out.resolve("ai_topics_by_country.csv").toFile());
        Table.create("common_topics",
                        StringColumn.create("topic", byCountry.commonTopics()),
                        StringColumn.create("decision", byCountry.commonTopics().stream().map(AiTopics::decision).toList()))
                .write().csv(out.resolve("ai_topics_by_country_common_topics.csv").toFile());
        regressions.write().csv(out.resolve("event_regressions.csv").toFile());
        AiActivity.eventFits(placeboFits)
                .removeColumns("placebo_dates", "placebo_share_p_level", "placebo_share_p_slope")
                .write().csv(out.resolve("placebo_breaks.csv").toFile());
        Table[] review = topicReview(topicsByQuarter);
        review[2].write().csv(out.resolve("ai_topic_groups.csv").toFile());
        review[0].write().csv(out.resolve("ai_topic_review.csv").toFile());
        review[1].write().csv(out.resolve("non_thematic_topics.csv").toFile());
        List<Path> charts = Task1Charts.write(activity, byQuarter, byCountry.table(), countryQuarter,
                byCountry.commonTopics().size(), figures);

        System.out.printf("Task 1: %s..%s, %d quarters; CSV written to %s%n",
                first, last, activity.rowCount(), out.toAbsolutePath());
        System.out.println(byQuarter.selectColumns("quarter_label", "ai_share_pct", "ai_per_1000_developers",
                "classic_ml_share_pct", "general_ai_share_pct", "llm_share_pct", "agents_share_pct").print());
        System.out.println(countries.print());
        System.out.println("common topics: " + byCountry.commonTopics());
        System.out.println(regressions.print());
        charts.forEach(c -> System.out.println("figure: " + c.toAbsolutePath().normalize()));
    }

    /** The same series around Copilot preview, ChatGPT and coding agents, each in its own window. */
    private static List<AiActivity.EventFit> threeEvents(Table table, String series, String first, String last) {
        return List.of(
                AiActivity.eventFit(table, series, "copilot_preview", first, COPILOT_WINDOW_END, COPILOT_PREVIEW),
                AiActivity.eventFit(table, series, "chatgpt", first, CHATGPT_WINDOW_END, CHATGPT),
                AiActivity.eventFit(table, series, "coding_agents", CHATGPT, last, CODING_AGENTS));
    }

    /**
     * [0] every topic of the data that matches {@link AiTopics#CANDIDATE} or is in an AI group, with its decision;
     * [1] every non-thematic topic left out of the shares; [2] the AI topics of the data by group. All with the total
     * mentions over all quarters.
     */
    private static Table[] topicReview(Table topicsByQuarter) {
        Map<String, Long> totals = new TreeMap<>();
        for (Row row : topicsByQuarter) {
            totals.merge(row.getString("topic"), row.getLong("pushers"), Long::sum);
        }
        StringColumn candidate = StringColumn.create("topic");
        LongColumn candidateTotal = LongColumn.create("total_pushers");
        StringColumn decision = StringColumn.create("decision");
        StringColumn nonThematic = StringColumn.create("topic");
        LongColumn nonThematicTotal = LongColumn.create("total_pushers");
        StringColumn aiGroup = StringColumn.create("group");
        StringColumn aiTopic = StringColumn.create("topic");
        LongColumn aiTotal = LongColumn.create("total_pushers");
        totals.forEach((topic, total) -> {
            if (AiTopics.isNonThematic(topic)) {
                nonThematic.append(topic);
                nonThematicTotal.append(total);
            } else if (AiTopics.CANDIDATE.matcher(topic).find() || AiTopics.group(topic).isPresent()) {
                candidate.append(topic);
                candidateTotal.append(total);
                decision.append(AiTopics.decision(topic));
            }
            AiTopics.group(topic).ifPresent(g -> {
                aiGroup.append(g.column());
                aiTopic.append(topic);
                aiTotal.append(total);
            });
        });
        return new Table[]{
                Table.create("ai_topic_review", candidate, candidateTotal, decision).sortDescendingOn("total_pushers"),
                Table.create("non_thematic_topics", nonThematic, nonThematicTotal).sortDescendingOn("total_pushers"),
                Table.create("ai_topic_groups", aiGroup, aiTopic, aiTotal).sortOn("group", "-total_pushers")};
    }
}
