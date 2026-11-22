package ru.spbstu.ghanalytics.gharchive;

import org.junit.jupiter.api.Test;
import ru.spbstu.ghanalytics.gharchive.dto.ActivityRow;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActivityMapperTest {

    @Test
    void hourlyRowsAreUtcWithNullCommits() {
        QueryResult r = QueryResult.parseTsv("ts_hour\tevent_type\tis_bot\tevents\tactors\tcommits\n"
                + "2024-03-06 15:00:00\tPushEvent\t1\t25000\t1200\t31000\n"
                + "2024-03-06 15:00:00\tWatchEvent\t0\t9000\t8500\t\\N\n", 0);

        assertThat(ActivityMapper.toHourly(r)).containsExactly(
                new ActivityRow.Hourly(Instant.parse("2024-03-06T15:00:00Z"), "PushEvent", true, 25000, 1200, 31000L),
                new ActivityRow.Hourly(Instant.parse("2024-03-06T15:00:00Z"), "WatchEvent", false, 9000, 8500, null));
    }

    @Test
    void monthlyRowsKeepEventTypeOfNewRepos() {
        QueryResult r = QueryResult.parseTsv("month\tevent_type\tis_bot\tevents\tactors\trepos\tcommits\n"
                + "2018-01-01\tCreateEvent:repository\t0\t1900000\t700000\t1890000\t\\N\n", 0);

        assertThat(ActivityMapper.toMonthly(r)).containsExactly(new ActivityRow.Monthly(
                LocalDate.of(2018, 1, 1), "CreateEvent:repository", false, 1_900_000, 700_000, 1_890_000, null));
    }

    @Test
    void emptyResultGivesNoRows() {
        assertThat(ActivityMapper.toHourly(QueryResult.parseTsv("ts_hour\tevent_type\tis_bot\tevents\tactors\tcommits\n", 0)))
                .isEmpty();
    }

    @Test
    void brokenRowIsRejected() {
        assertThatThrownBy(() -> QueryResult.parseTsv("a\tb\n1\t2\nCode: 159. DB::Exception: Timeout exceeded\n", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 3");
    }

    @Test
    void unknownColumnNamesTheColumnsThatExist() {
        QueryResult r = QueryResult.parseTsv("a\tb\n1\t2\n", 0);

        assertThatThrownBy(() -> r.get(r.rows().get(0), "c"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("[a, b]");
    }
}
