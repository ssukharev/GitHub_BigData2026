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
        assertTrue(AiTopics.topics(AiTopics.Group.AGENTS).contains("mcp"));
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
    void aiShareByCountrySkipsSmallCountries() {
        Table rows = Table.create("topics_by_country",
                StringColumn.create("country_code", "AA", "AA", "BB", "BB", "CC"),
                StringColumn.create("name", "A", "A", "B", "B", "C"),
                StringColumn.create("topic", "python", "llm", "python", "mcp", "llm"),
                LongColumn.create("pushers", 9_000L, 3_000L, 15_000L, 1_000L, 500L));

        Table byCountry = AiActivity.aiShareByCountry(rows, 10_000);

        assertEquals(List.of("AA", "BB"), byCountry.stringColumn("country_code").asList());
        assertEquals(25.0, byCountry.doubleColumn("ai_share_pct").get(0), 1e-9);
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
}
