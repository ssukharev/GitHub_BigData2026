package ru.spbstu.ghanalytics.github;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The plan is a pure function of the period, the seed and the width: no GitHub, no Spring. */
class SampleWindowPlannerTest {

    private static final DateRange MARCH = new DateRange(LocalDate.of(2023, 3, 1), LocalDate.of(2023, 3, 31));

    private static SampleWindowPlanner planner(long seed) {
        return new SampleWindowPlanner(seed, Duration.ofMinutes(10));
    }

    private static List<TimeWindow> chronological(List<TimeWindow> windows) {
        return windows.stream().sorted(Comparator.comparing(TimeWindow::from)).toList();
    }

    @Test
    void sameSeedGivesSamePlan() {
        assertThat(planner(42).windows(MARCH)).isEqualTo(planner(42).windows(MARCH));
    }

    @Test
    void differentSeedGivesDifferentOrder() {
        assertThat(planner(42).windows(MARCH)).isNotEqualTo(planner(43).windows(MARCH));
    }

    @Test
    void windowsTileStratumWithoutGapsAndOverlaps() {
        List<TimeWindow> sorted = chronological(planner(42).windows(MARCH));

        assertThat(sorted).hasSize(31 * 144);
        assertThat(sorted.getFirst().from()).isEqualTo(Instant.parse("2023-03-01T00:00:00Z"));
        assertThat(sorted.getLast().to()).isEqualTo(Instant.parse("2023-03-31T23:59:59Z"));
        assertThat(sorted).allSatisfy(w -> assertThat(w.seconds()).isEqualTo(600));
        for (int i = 1; i < sorted.size(); i++) {
            assertThat(sorted.get(i).from()).as("window %d starts a second after window %d ends", i, i - 1)
                    .isEqualTo(sorted.get(i - 1).to().plusSeconds(1));
        }
    }

    @Test
    void windowsOfAClippedStratumStayInsideIt() {
        DateRange clipped = new DateRange(LocalDate.of(2018, 1, 15), LocalDate.of(2018, 1, 31));
        Instant start = Instant.parse("2018-01-15T00:00:00Z");
        Instant end = Instant.parse("2018-01-31T23:59:59Z");

        List<TimeWindow> windows = planner(42).windows(clipped);

        assertThat(windows).hasSize(17 * 144).doesNotHaveDuplicates()
                .allSatisfy(w -> assertThat(w.from()).isBetween(start, end))
                .allSatisfy(w -> assertThat(w.to()).isBetween(start, end));
    }

    @Test
    void orderIsShuffledNotChronological() {
        List<TimeWindow> plan = planner(42).windows(MARCH);

        assertThat(plan.subList(0, 20)).isNotEqualTo(chronological(plan).subList(0, 20));
    }

    @Test
    void firstWindowIsUniformOverStratum() {
        // catches "only the start of the list was shuffled" and a drift to one end of the month
        Instant middle = Instant.parse("2023-03-16T12:00:00Z");
        long firstHalf = IntStream.rangeClosed(1, 2000)
                .filter(seed -> planner(seed).windows(MARCH).getFirst().from().isBefore(middle))
                .count();

        assertThat(firstHalf / 2000.0).isBetween(0.45, 0.55);
    }

    @Test
    void monthsOfOneSeedAreShuffledIndependently() {
        // the same seed must not put the first window at the same place of every month
        SampleWindowPlanner planner = planner(42);
        Set<Long> offsets = new HashSet<>();
        for (DateRange month : SampleWindowPlanner.strata(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31))) {
            Instant start = month.from().atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
            offsets.add(Duration.between(start, planner.windows(month).getFirst().from()).toSeconds());
        }

        assertThat(offsets).hasSizeGreaterThan(6);
    }

    @Test
    void strataCoverPeriodByMonths() {
        List<DateRange> strata = SampleWindowPlanner.strata(LocalDate.of(2018, 1, 1), LocalDate.of(2025, 12, 31));

        assertThat(strata).hasSize(96);
        assertThat(strata.getFirst()).isEqualTo(new DateRange(LocalDate.of(2018, 1, 1), LocalDate.of(2018, 1, 31)));
        assertThat(strata.getLast()).isEqualTo(new DateRange(LocalDate.of(2025, 12, 1), LocalDate.of(2025, 12, 31)));
        for (int i = 1; i < strata.size(); i++) {
            assertThat(strata.get(i).from()).isEqualTo(strata.get(i - 1).to().plusDays(1));
            assertThat(YearMonth.from(strata.get(i).from())).isEqualTo(YearMonth.from(strata.get(i).to()));
        }
    }

    @Test
    void strataAreClippedByPeriodBounds() {
        assertThat(SampleWindowPlanner.strata(LocalDate.of(2018, 1, 15), LocalDate.of(2018, 3, 10))).containsExactly(
                new DateRange(LocalDate.of(2018, 1, 15), LocalDate.of(2018, 1, 31)),
                new DateRange(LocalDate.of(2018, 2, 1), LocalDate.of(2018, 2, 28)),
                new DateRange(LocalDate.of(2018, 3, 1), LocalDate.of(2018, 3, 10)));
        assertThat(SampleWindowPlanner.strata(LocalDate.of(2024, 2, 10), LocalDate.of(2024, 2, 10)))
                .containsExactly(new DateRange(LocalDate.of(2024, 2, 10), LocalDate.of(2024, 2, 10)));
    }

    @Test
    void widthMustDivideTheDay() {
        assertThatThrownBy(() -> new SampleWindowPlanner(42, Duration.ofMinutes(7)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SampleWindowPlanner(42, Duration.ofSeconds(90)))
                .as("whole minutes only").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SampleWindowPlanner(42, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new SampleWindowPlanner(42, Duration.ofMinutes(60)).windows(MARCH)).hasSize(31 * 24);
        assertThat(SampleWindowPlanner.DEFAULT_WIDTH).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void allocateKeepsSumByCumulativeRounding() {
        assertThat(SampleWindowPlanner.allocate(List.of(1, 1, 1), 10)).containsExactly(3, 4, 3);
        assertThat(SampleWindowPlanner.allocate(List.of(1, 3), 4)).containsExactly(1, 3);

        List<Integer> quotas = SampleWindowPlanner.allocate(Collections.nCopies(96, 1), 5000);
        assertThat(quotas).hasSize(96).allSatisfy(q -> assertThat(q).isBetween(52, 53));
        assertThat(quotas.stream().mapToInt(Integer::intValue).sum()).isEqualTo(5000);
    }

    @Test
    void allocateSmallLimitAndBadInput() {
        assertThat(SampleWindowPlanner.allocate(Collections.nCopies(96, 1), 100).stream().mapToInt(Integer::intValue).sum())
                .isEqualTo(100);
        assertThat(SampleWindowPlanner.allocate(List.of(1, 1), 0)).containsExactly(0, 0);
        assertThatThrownBy(() -> SampleWindowPlanner.allocate(List.of(1, -1), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SampleWindowPlanner.allocate(List.of(0, 0), 10)).isInstanceOf(IllegalArgumentException.class);
    }
}
