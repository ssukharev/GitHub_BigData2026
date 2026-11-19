package ru.spbstu.ghanalytics.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure functions of the collector on the fixture {@code stats-contributors.json} (the shortened answer
 * of {@code /stats/contributors} from the chapter: alice and bob, two weeks from 2021-01-03).
 */
class CommitActivityCollectorTest {

    private static final LocalDate JAN_3 = LocalDate.of(2021, 1, 3);
    private static final LocalDate JAN_10 = LocalDate.of(2021, 1, 10);

    private final ObjectMapper json = JsonMapper.builder().findAndAddModules().build();

    @Test
    void sumsCommitsAndCountsAuthorsWithCommitsByWeek() throws IOException {
        assertThat(CommitActivityCollector.aggregate(7, fixture())).containsExactly(
                new WeekRow(7, JAN_3, 5, 2),    // 3 + 2, both authors
                new WeekRow(7, JAN_10, 4, 1));  // 0 + 4, only bob
    }

    @Test
    void weeksAreSundaysInUtcInAscendingOrder() throws IOException {
        assertThat(CommitActivityCollector.aggregate(7, fixture()))
                .extracting(WeekRow::week)
                .containsExactly(JAN_3, JAN_10)
                .allSatisfy(week -> assertThat(week.getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY));
    }

    @Test
    void aWeekWithoutCommitsIsARowWithZerosNotAGap() throws IOException {
        JsonNode stats = json.readTree("""
                [{"author": {"login": "alice"}, "total": 3, "weeks": [
                    {"w": 1609632000, "a": 1, "d": 0, "c": 2},
                    {"w": 1610236800, "a": 0, "d": 0, "c": 0},
                    {"w": 1610841600, "a": 1, "d": 0, "c": 1}]}]""");

        assertThat(CommitActivityCollector.aggregate(7, stats)).containsExactly(
                new WeekRow(7, JAN_3, 2, 1),
                new WeekRow(7, JAN_10, 0, 0),
                new WeekRow(7, LocalDate.of(2021, 1, 17), 1, 1));
    }

    @Test
    void weeksOfAuthorsInAnyOrderComeOutSorted() throws IOException {
        JsonNode stats = json.readTree("""
                [{"author": null, "total": 1, "weeks": [{"w": 1610236800, "a": 0, "d": 0, "c": 1}]},
                 {"author": {"login": "bob"}, "total": 1, "weeks": [{"w": 1609632000, "a": 0, "d": 0, "c": 1}]}]""");

        assertThat(CommitActivityCollector.aggregate(7, stats)).containsExactly(
                new WeekRow(7, JAN_3, 1, 1),
                new WeekRow(7, JAN_10, 1, 1));
    }

    @Test
    void anEmptyArrayGivesNoRows() throws IOException {
        assertThat(CommitActivityCollector.aggregate(7, json.readTree("[]"))).isEmpty();
    }

    private JsonNode fixture() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/stats-contributors.json")) {
            return json.readTree(in);
        }
    }
}
