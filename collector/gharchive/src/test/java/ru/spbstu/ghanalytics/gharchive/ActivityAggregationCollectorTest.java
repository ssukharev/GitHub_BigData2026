package ru.spbstu.ghanalytics.gharchive;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.spbstu.ghanalytics.core.CollectionContext;
import ru.spbstu.ghanalytics.core.run.CheckpointStore;
import ru.spbstu.ghanalytics.core.run.CollectionRunService;
import ru.spbstu.ghanalytics.core.run.RunStats;
import ru.spbstu.ghanalytics.gharchive.ActivityAggregationCollector.EventClass;
import ru.spbstu.ghanalytics.gharchive.ActivityAggregationCollector.Task;
import ru.spbstu.ghanalytics.gharchive.Periods.Chunk;
import ru.spbstu.ghanalytics.gharchive.dto.ActivityRow;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ActivityAggregationCollectorTest {

    private static final Instant NOW = Instant.parse("2026-11-16T12:00:00Z");
    private static final GhArchiveProperties PROPS =
            new GhArchiveProperties(URI.create("https://play.clickhouse.com/"), "play", LocalDate.of(2026, 7, 31));

    private final ClickHouseClient clickHouse = mock(ClickHouseClient.class);
    private final ActivityRepository repository = mock(ActivityRepository.class);
    private final CheckpointStore checkpoints = mock(CheckpointStore.class);
    private final CollectionRunService runs = mock(CollectionRunService.class);
    private final RunStats stats = new RunStats();
    private final List<Duration> sleeps = new ArrayList<>();
    private ActivityAggregationCollector collector;

    @BeforeEach
    void setUp() {
        collector = new ActivityAggregationCollector(clickHouse, repository, checkpoints, runs, stats, PROPS,
                sleeps::add, Clock.fixed(NOW, ZoneOffset.UTC));
        when(clickHouse.estimateRows(anyString(), anyMap())).thenReturn(1L);
        when(clickHouse.quotaLeftRows()).thenReturn(300_000_000_000L);
        when(checkpoints.get(anyString(), anyString())).thenReturn(Optional.empty());
        when(runs.start(anyString(), anyMap())).thenReturn(42L);
    }

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    private static Chunk chunk(String from, String to) {
        return new Chunk(d(from), d(to));
    }

    private static String describe(Task t) {
        return t.sqlName() + ":" + t.eventClass() + ":" + t.chunk();
    }

    private static QueryResult hourlyResult(String... lines) {
        return QueryResult.parseTsv("ts_hour\tevent_type\tis_bot\tevents\tactors\tcommits\n"
                + String.join("\n", lines), 7_000_000_000L);
    }

    @Test
    void planHasYearlyHourlyTasksAndMonthlyTasksOfWholeMonthsOnly() {
        List<Task> tasks = collector.plan(d("2018-03-10"), d("2019-02-20"), false);

        assertThat(tasks).extracting(ActivityAggregationCollectorTest::describe).containsExactly(
                "hourly_activity:PUSH:2018-03-10..2018-12-31",
                "hourly_activity:OTHER:2018-03-10..2018-12-31",
                "monthly_activity:PUSH:2018-04-01..2018-12-31",
                "monthly_activity:OTHER:2018-04-01..2018-12-31",
                "hourly_activity:PUSH:2019-01-01..2019-02-20",
                "hourly_activity:OTHER:2019-01-01..2019-02-20",
                "monthly_activity:PUSH:2019-01-01..2019-01-31",
                "monthly_activity:OTHER:2019-01-01..2019-01-31",
                "monthly_new_repos:ALL:2018-04-01..2019-01-31");
        assertThat(tasks).allSatisfy(t -> assertThat(t.estimatedRows()).isEqualTo(1L));
    }

    @Test
    void planOfOneDayHasTwoHourlyTasks() {
        assertThat(collector.plan(d("2024-03-06"), d("2024-03-06"), false))
                .extracting(ActivityAggregationCollectorTest::describe)
                .containsExactly("hourly_activity:PUSH:2024-03-06..2024-03-06", "hourly_activity:OTHER:2024-03-06..2024-03-06");
    }

    @Test
    void newReposStopAfterApril2025() {
        assertThat(collector.plan(d("2025-05-01"), d("2025-12-31"), false))
                .noneMatch(t -> t.sqlName().equals(ActivityAggregationCollector.NEW_REPOS));
        assertThat(collector.plan(d("2025-01-01"), d("2025-12-31"), false))
                .filteredOn(t -> t.sqlName().equals(ActivityAggregationCollector.NEW_REPOS))
                .extracting(Task::chunk)
                .containsExactly(chunk("2025-01-01", "2025-04-30"));
    }

    @Test
    void fullPeriodHas37Tasks() {
        assertThat(collector.plan(d("2018-01-01"), d("2026-07-31"), false)).hasSize(37);
    }

    @Test
    void resumeSkipsOnlyTheTasksWithCheckpoint() {
        when(checkpoints.get("gharchive", "hourly_activity:PUSH:2024-01-01..2024-12-31")).thenReturn(Optional.of("17566"));

        List<Task> tasks = collector.plan(d("2024-01-01"), d("2024-12-31"), true);

        assertThat(tasks).extracting(ActivityAggregationCollectorTest::describe)
                .doesNotContain("hourly_activity:PUSH:2024-01-01..2024-12-31")
                .contains("hourly_activity:OTHER:2024-01-01..2024-12-31", "monthly_activity:PUSH:2024-01-01..2024-12-31");
        verify(clickHouse, never()).estimateRows(contains("event_type = 'PushEvent'\n    GROUP BY ts_hour"), anyMap());
    }

    @Test
    void withoutResumeCheckpointsAreNotRead() {
        collector.plan(d("2024-01-01"), d("2024-12-31"), false);

        verify(checkpoints, never()).get(anyString(), anyString());
    }

    @Test
    void sqlGetsPredicateAndExclusiveEnd() {
        Task push = new Task(ActivityAggregationCollector.HOURLY, EventClass.PUSH, chunk("2024-01-01", "2024-12-31"), 0);

        assertThat(collector.renderSql(push))
                .contains("AND event_type = 'PushEvent'")
                .doesNotContain("{{event_class}}")
                .endsWith("FORMAT TSVWithNames\n");
        assertThat(ActivityAggregationCollector.params(push))
                .containsEntry("from", "2024-01-01 00:00:00")
                .containsEntry("to", "2025-01-01 00:00:00");
        Task other = new Task(ActivityAggregationCollector.MONTHLY, EventClass.OTHER, chunk("2024-01-01", "2024-12-31"), 0);
        assertThat(collector.renderSql(other)).contains("AND event_type != 'PushEvent'");
    }

    @Test
    void waitForQuotaIsUntilWindowEndPlusFiveSeconds() {
        ClickHouseQuotaException withEnd = new ClickHouseQuotaException(500, "quota", NOW.plusSeconds(600));
        ClickHouseQuotaException withoutEnd = new ClickHouseQuotaException(500, "quota", null);
        ClickHouseQuotaException alreadyOver = new ClickHouseQuotaException(500, "quota", NOW.minusSeconds(1));

        assertThat(collector.waitFor(withEnd)).isEqualTo(Duration.ofSeconds(605));
        assertThat(collector.waitFor(withoutEnd)).isEqualTo(Duration.ofMinutes(5));
        assertThat(collector.waitFor(alreadyOver)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void collectLoadsUpsertsCheckpointsAndFinishesRun() {
        when(clickHouse.query(contains("toStartOfHour"), anyMap())).thenReturn(hourlyResult(
                "2024-03-06 00:00:00\tPushEvent\t0\t100\t80\t150",
                "2024-03-06 00:00:00\tPushEvent\t1\t20\t3\t20"));
        when(repository.upsertHourly(any())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());

        collector.collect(new CollectionContext(d("2024-03-06"), d("2024-03-06"), 0, 0, false));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ActivityRow.Hourly>> rows = ArgumentCaptor.forClass(List.class);
        verify(repository, times(2)).upsertHourly(rows.capture());
        assertThat(rows.getAllValues().get(0)).first().isEqualTo(new ActivityRow.Hourly(
                Instant.parse("2024-03-06T00:00:00Z"), "PushEvent", false, 100, 80, 150L));
        verify(checkpoints).put("gharchive", "hourly_activity:PUSH:2024-03-06..2024-03-06", "2");
        verify(checkpoints).put("gharchive", "hourly_activity:OTHER:2024-03-06..2024-03-06", "2");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> finishStats = ArgumentCaptor.forClass(Map.class);
        verify(runs).finish(eq(42L), eq(4L), finishStats.capture());
        assertThat(finishStats.getValue()).containsEntry("rows_estimated", (Object) 2L);
        verify(runs, never()).fail(anyLong(), anyString());
    }

    @Test
    void periodIsCutToConfiguredLastDayAndJournalGetsIt() {
        when(clickHouse.query(anyString(), anyMap())).thenReturn(hourlyResult());

        collector.collect(new CollectionContext(d("2026-07-31"), d("2026-10-31"), 0, 0, false));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(runs).start(eq("gharchive"), params.capture());
        assertThat(params.getValue()).containsEntry("from", "2026-07-31").containsEntry("to", "2026-07-31");
        verify(clickHouse, times(2)).query(anyString(), eq(Map.of("from", "2026-07-31 00:00:00", "to", "2026-08-01 00:00:00")));
    }

    @Test
    void periodIsCutToYesterdayUtc() {
        GhArchiveProperties late = new GhArchiveProperties(URI.create("https://play.clickhouse.com/"), "play", d("2030-01-01"));
        ActivityAggregationCollector c = new ActivityAggregationCollector(clickHouse, repository, checkpoints, runs, stats,
                late, sleeps::add, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(c.effectiveTo(new CollectionContext(d("2026-11-01"), d("2026-11-16"), 0, 0, false)))
                .isEqualTo(d("2026-11-15"));
    }

    @Test
    void periodStartingAfterLastDayIsRejectedBeforeTheRun() {
        assertThatThrownBy(() -> collector.collect(new CollectionContext(d("2026-08-01"), d("2026-08-31"), 0, 0, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2026-07-31");
        verify(runs, never()).start(anyString(), anyMap());
    }

    @Test
    void waitsForQuotaWindowAndRepeatsTheTask() {
        when(clickHouse.query(anyString(), anyMap()))
                .thenThrow(new ClickHouseQuotaException(500, "quota", NOW.plusSeconds(120)))
                .thenReturn(hourlyResult());

        collector.collect(new CollectionContext(d("2024-03-06"), d("2024-03-06"), 0, 0, false));

        assertThat(sleeps).containsExactly(Duration.ofSeconds(125));
        assertThat(stats.snapshot()).containsEntry("rate_limit_wait_s", 125L);
        verify(runs).finish(eq(42L), eq(0L), anyMap());
    }

    @Test
    void givesUpAfterTwoQuotaWaitsAndMarksRunFailed() {
        when(clickHouse.query(anyString(), anyMap())).thenThrow(new ClickHouseQuotaException(500, "quota", null));

        assertThatThrownBy(() -> collector.collect(new CollectionContext(d("2024-03-06"), d("2024-03-06"), 0, 0, false)))
                .isInstanceOf(ClickHouseQuotaException.class);

        assertThat(sleeps).containsExactly(Duration.ofMinutes(5), Duration.ofMinutes(5));
        verify(clickHouse, times(3)).query(anyString(), anyMap());
        verify(runs).fail(eq(42L), contains("ClickHouseQuotaException"));
        verify(checkpoints, never()).put(anyString(), anyString(), anyString());
    }

    @Test
    void sqlErrorFailsTheRunWithoutCheckpoint() {
        when(clickHouse.query(anyString(), anyMap())).thenThrow(new ClickHouseException(500, 10, "Code: 10."));

        assertThatThrownBy(() -> collector.collect(new CollectionContext(d("2024-03-06"), d("2024-03-06"), 0, 0, false)))
                .isInstanceOf(ClickHouseException.class);

        verify(runs).fail(eq(42L), contains("Code: 10."));
        verify(checkpoints, never()).put(anyString(), anyString(), anyString());
        verify(runs, never()).finish(anyLong(), anyLong(), anyMap());
    }
}
