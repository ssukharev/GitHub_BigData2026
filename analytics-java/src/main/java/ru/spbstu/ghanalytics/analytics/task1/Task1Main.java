package ru.spbstu.ghanalytics.analytics.task1;

import ru.spbstu.ghanalytics.analytics.DbConfig;
import ru.spbstu.ghanalytics.analytics.Sql;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Task 1 tables, written as CSV to {@code out}, and figures (PNG) to {@code figures}.
 * Started from {@link ru.spbstu.ghanalytics.analytics.Main}.
 */
public final class Task1Main {

    /** ChatGPT came out on 2022-11-30: 2023Q1 is the first full quarter after it. */
    static final String CHATGPT = "2023Q1";
    /** Agent tools for code went public in Feb-May 2025 (Copilot agent mode, Claude Code, Codex): 2025Q2. */
    static final String CODING_AGENTS = "2025Q2";

    private Task1Main() {
    }

    public static void run(DbConfig db, Path out, Path figures) throws SQLException, IOException {
        Table activity;
        Table byQuarter;
        Table byCountry;
        try (Connection conn = db.open()) {
            activity = AiActivity.activity(Sql.table(conn, "task1/activity_by_quarter"));
            byQuarter = AiActivity.aiShareByQuarter(Sql.table(conn, "task1/topics_by_quarter"));
            byCountry = AiActivity.aiShareByCountry(Sql.table(conn, "task1/topics_by_country"),
                    AiActivity.MIN_COUNTRY_TOPIC_PUSHERS);
        }
        if (activity.isEmpty() || byQuarter.isEmpty()) {
            throw new IllegalStateException("No data: load collector/innovation-graph first");
        }
        String first = activity.stringColumn("quarter_label").get(0);
        String last = activity.stringColumn("quarter_label").get(activity.rowCount() - 1);
        // the ChatGPT window stops before the agent wave, the agent window starts at ChatGPT
        String beforeAgents = "2024Q4";

        List<AiActivity.EventFit> fits = new ArrayList<>();
        for (String series : List.of("pushes_per_developer", "new_developers", "repositories_per_developer")) {
            fits.add(AiActivity.eventFit(activity, series, "chatgpt", first, beforeAgents, CHATGPT));
            fits.add(AiActivity.eventFit(activity, series, "coding_agents", CHATGPT, last, CODING_AGENTS));
        }
        fits.add(AiActivity.eventFit(byQuarter, "ai_share_pct", "chatgpt", first, beforeAgents, CHATGPT));
        fits.add(AiActivity.eventFit(byQuarter, "llm_share_pct", "chatgpt", first, beforeAgents, CHATGPT));
        fits.add(AiActivity.eventFit(byQuarter, "ai_share_pct", "coding_agents", CHATGPT, last, CODING_AGENTS));
        fits.add(AiActivity.eventFit(byQuarter, "agents_share_pct", "coding_agents", CHATGPT, last, CODING_AGENTS));
        Table regressions = AiActivity.eventFits(fits);

        Files.createDirectories(out);
        activity.write().csv(out.resolve("activity_by_quarter.csv").toFile());
        byQuarter.write().csv(out.resolve("ai_topics_by_quarter.csv").toFile());
        byCountry.write().csv(out.resolve("ai_topics_by_country_" + last + ".csv").toFile());
        regressions.write().csv(out.resolve("event_regressions.csv").toFile());
        topicGroups().write().csv(out.resolve("ai_topic_groups.csv").toFile());
        List<Path> charts = Task1Charts.write(activity, byQuarter, byCountry, last, figures);

        System.out.printf("Task 1: %s..%s, %d quarters; CSV written to %s%n",
                first, last, activity.rowCount(), out.toAbsolutePath());
        System.out.println(activity.selectColumns("quarter_label", "developers", "new_developers", "git_pushes",
                "pushes_per_developer").print());
        System.out.println(byQuarter.selectColumns("quarter_label", "ai_share_pct", "classic_ml_share_pct",
                "general_ai_share_pct", "llm_share_pct", "agents_share_pct").print());
        System.out.println(byCountry.first(10).print());
        System.out.println(regressions.print());
        charts.forEach(c -> System.out.println("figure: " + c.toAbsolutePath().normalize()));
    }

    private static Table topicGroups() {
        StringColumn group = StringColumn.create("group");
        StringColumn topic = StringColumn.create("topic");
        for (AiTopics.Group g : AiTopics.Group.values()) {
            for (String t : AiTopics.topics(g)) {
                group.append(g.column());
                topic.append(t);
            }
        }
        return Table.create("ai_topic_groups", group, topic);
    }
}
