package ru.spbstu.ghanalytics.analytics.task1;

import org.junit.jupiter.api.Test;
import tech.tablesaw.api.IntColumn;
import tech.tablesaw.api.LongColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiActivityTest {

    @Test
    void groupsTopics() {
        assertEquals(Optional.of(AiTopics.Group.LLM), AiTopics.group("llm"));
        assertEquals(Optional.of(AiTopics.Group.AGENTS), AiTopics.group("claude-code"));
        assertEquals(Optional.of(AiTopics.Group.GENERAL_AI), AiTopics.group("ai"));
        assertEquals(Optional.of(AiTopics.Group.CLASSIC_ML), AiTopics.group("pytorch"));
        assertEquals(Optional.empty(), AiTopics.group("python"));
        assertTrue(AiTopics.fixedTopics(AiTopics.Group.AGENTS).contains("mcp"));
    }

    @Test
    void groupsUnlistedTopicsByTheWordsOfTheName() {
        assertEquals(Optional.of(AiTopics.Group.AGENTS), AiTopics.group("agentic-workflow"));
        assertEquals(Optional.of(AiTopics.Group.AGENTS), AiTopics.group("claude-skill"), "model + tool word");
        assertEquals(Optional.of(AiTopics.Group.AGENTS), AiTopics.group("gemini-cli"));
        assertEquals(Optional.of(AiTopics.Group.LLM), AiTopics.group("local-llm"));
        assertEquals(Optional.of(AiTopics.Group.LLM), AiTopics.group("claude-api"));
        assertEquals(Optional.of(AiTopics.Group.LLM), AiTopics.group("diffusion-models"));
        assertEquals(Optional.of(AiTopics.Group.CLASSIC_ML), AiTopics.group("graph-neural-networks"));
        assertEquals(Optional.of(AiTopics.Group.GENERAL_AI), AiTopics.group("ai-safety"));
    }

    @Test
    void rejectedAndNonThematicTopicsAreNotAi() {
        for (String topic : List.of("virtual-machine", "state-machine", "lora", "apm-agent", "causal-inference",
                "language-learning", "config", "learning")) {
            assertEquals(Optional.empty(), AiTopics.group(topic), topic);
        }
        assertTrue(AiTopics.decision("lora").startsWith("rejected: "));
        assertEquals("not AI", AiTopics.decision("python"));
    }

    @Test
    void recognisesNonThematicTopics() {
        for (String topic : List.of("config", "github-config", "hacktoberfest", "hacktoberfest2021",
                "hacktoberfest-accepted", "portfolio-website", "readme-profile", "template", "challengeonecodificador5",
                "good-first-issue")) {
            assertTrue(AiTopics.isNonThematic(topic), topic);
        }
        for (String topic : List.of("python", "dotfiles", "configuration", "ai", "github-actions")) {
            assertEquals(false, AiTopics.isNonThematic(topic), topic);
        }
    }

    @Test
    void decimalYearOfQuarter() {
        assertEquals(2023.0, AiActivity.decimalYear("2023Q1"), 1e-12);
        assertEquals(2025.25, AiActivity.decimalYear("2025Q2"), 1e-12);
        assertEquals(2026.75, AiActivity.decimalYear(AiActivity.quarterLabel(2026, 4)), 1e-12);
    }

    @Test
    void activityAddsRatiosAndNetChange() {
        Table raw = Table.create("activity_by_quarter",
                IntColumn.create("year", 2020, 2020),
                IntColumn.create("quarter", 2, 1),           // unordered on purpose
                IntColumn.create("countries", 10, 10),
                LongColumn.create("developers", 1_100L, 1_000L),
                LongColumn.create("repositories", 2_200L, 2_500L),
                LongColumn.create("git_pushes", 1_800L, 1_500L),
                LongColumn.create("developers_with_pushes", 900L, 1_000L));

        Table activity = AiActivity.activity(raw);

        assertEquals(List.of("2020Q1", "2020Q2"), activity.stringColumn("quarter_label").asList());
        assertTrue(activity.longColumn("developers_net_change").isMissing(0));
        assertEquals(100L, activity.longColumn("developers_net_change").get(1));
        assertEquals(1.5, activity.doubleColumn("pushes_per_developer").get(0), 1e-9);
        assertEquals(2.0, activity.doubleColumn("pushes_per_developer").get(1), 1e-9);  // 1800 / 900
        assertEquals(2.0, activity.doubleColumn("repositories_per_developer").get(1), 1e-9);
    }

    private static Table topics(Object[]... rows) {
        Table t = Table.create("topics", IntColumn.create("year"), IntColumn.create("quarter"),
                StringColumn.create("topic"), LongColumn.create("pushers"));
        for (Object[] r : rows) {
            t.intColumn("year").append((Integer) r[0]);
            t.intColumn("quarter").append((Integer) r[1]);
            t.stringColumn("topic").append((String) r[2]);
            t.longColumn("pushers").append((Long) r[3]);
        }
        return t;
    }

    @Test
    void aiShareByQuarterLeavesNonThematicTopicsOutOfTheDenominator() {
        Table share = AiActivity.aiShareByQuarter(topics(
                new Object[]{2021, 3, "python", 900L},
                new Object[]{2021, 3, "machine-learning", 100L},
                new Object[]{2021, 3, "github-config", 9_000L},
                new Object[]{2021, 3, "hacktoberfest", 500L}));

        assertEquals(10.0, share.doubleColumn("ai_share_pct").get(0), 1e-9);   // 100 / 1000, not 100 / 10 500
        assertEquals(9_500L, share.longColumn("non_thematic_pushers").get(0));
        assertEquals(1_000L, share.longColumn("all_topic_pushers").get(0));
        assertEquals(2, share.intColumn("topics").get(0));
    }

    @Test
    void aiPerThousandDevelopers() {
        Table share = AiActivity.aiShareByQuarter(topics(
                new Object[]{2021, 3, "python", 900L},
                new Object[]{2021, 3, "llm", 50L}));
        Table activity = Table.create("activity", StringColumn.create("quarter_label", "2021Q3"),
                LongColumn.create("developers", 100_000L));

        Table result = AiActivity.withAiPerThousandDevelopers(share, activity);

        assertEquals(0.5, result.doubleColumn("ai_per_1000_developers").get(0), 1e-9);
    }

    @Test
    void aiShareByQuarterSplitsGroups() {
        Table share = AiActivity.aiShareByQuarter(topics(
                new Object[]{2023, 1, "python", 800L},
                new Object[]{2023, 1, "llm", 100L},
                new Object[]{2023, 1, "machine-learning", 100L},
                new Object[]{2022, 4, "python", 900L},
                new Object[]{2022, 4, "pytorch", 100L}));

        assertEquals(List.of("2022Q4", "2023Q1"), share.stringColumn("quarter_label").asList());
        assertEquals(10.0, share.doubleColumn("ai_share_pct").get(0), 1e-9);
        assertEquals(20.0, share.doubleColumn("ai_share_pct").get(1), 1e-9);
        assertEquals(10.0, share.doubleColumn("llm_share_pct").get(1), 1e-9);
        assertEquals(0.0, share.doubleColumn("agents_share_pct").get(1), 1e-9);
        assertEquals(3, share.intColumn("topics").get(1));
    }

    @Test
    void aiShareByCountryUsesTopicsPublishedForEveryCountry() {
        // BB has a long list (rust is published only there), AA a short one; CC is too small to compare
        Table rows = Table.create("topics_by_country",
                StringColumn.create("country_code", "AA", "AA", "BB", "BB", "BB", "BB", "CC"),
                StringColumn.create("name", "A", "A", "B", "B", "B", "B", "C"),
                StringColumn.create("topic", "python", "llm", "python", "llm", "rust", "config", "llm"),
                LongColumn.create("pushers", 9_000L, 3_000L, 6_000L, 2_000L, 20_000L, 50_000L, 500L));

        AiActivity.CountryShares shares = AiActivity.aiShareByCountry(rows, 10_000);
        Table t = shares.table();

        assertEquals(List.of("llm", "python"), shares.commonTopics());
        assertEquals(List.of("AA", "BB"), t.stringColumn("country_code").asList());
        assertEquals(25.0, t.doubleColumn("ai_share_pct").get(0), 1e-9);              // 3000 / 12 000
        assertEquals(25.0, t.doubleColumn("ai_share_pct").get(1), 1e-9);              // 2000 / 8000
        assertEquals(2_000.0 / 28_000 * 100, t.doubleColumn("ai_share_own_topics_pct").get(1), 1e-9);
        assertEquals(3, t.intColumn("topics_published").get(1), "config is non-thematic");
    }

    @Test
    void eventFitUsesTheWindow() {
        StringColumn label = StringColumn.create("quarter_label");
        tech.tablesaw.api.DoubleColumn y = tech.tablesaw.api.DoubleColumn.create("y");
        for (int i = 0; i < 12; i++) {
            label.append(AiActivity.quarterLabel(2020 + i / 4, i % 4 + 1));
            y.append(i < 6 ? i : i + 10);   // jump of 10 at 2021Q3
        }
        Table t = Table.create("t", label, y);

        AiActivity.EventFit fit = AiActivity.eventFit(t, "y", "test", "2020Q2", "2022Q3", "2021Q3");

        assertEquals(10, fit.fit().n());
        assertEquals(10.0, fit.fit().levelChange(), 1e-9);
        assertEquals("2020Q2..2022Q3", fit.window());
    }

    @Test
    void placeboMovesTheBreakToEveryOtherQuarter() {
        StringColumn label = StringColumn.create("quarter_label");
        tech.tablesaw.api.DoubleColumn y = tech.tablesaw.api.DoubleColumn.create("y");
        for (int i = 0; i < 12; i++) {
            label.append(AiActivity.quarterLabel(2020 + i / 4, i % 4 + 1));
            y.append(i < 6 ? i + (i % 2) * 0.1 : i + 10 + (i % 2) * 0.1);   // jump of 10 at 2021Q3
        }
        Table t = Table.create("t", label, y);
        AiActivity.EventFit real = AiActivity.eventFit(t, "y", "test", "2020Q1", "2022Q4", "2021Q3");

        AiActivity.Placebo p = AiActivity.placebo(t, real, 3);

        assertEquals(6, p.dates(), "breaks at quarters 3..9 except the real one at 6");
        assertTrue(p.fits().stream().noneMatch(f -> f.firstQuarterAfter().equals("2021Q3")));
        assertTrue(p.shareLevel() < 0.5, "the real jump stands out: " + p.shareLevel());
    }
}
