package ru.spbstu.ghanalytics.github;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link SearchSlicer} on a fake {@link RepoCounter}: counts are given as data, no API. */
class SearchSlicerTest {

    private static final DateRange MARCH = new DateRange(LocalDate.of(2021, 3, 1), LocalDate.of(2021, 3, 31));
    private static final DateRange YEAR = new DateRange(LocalDate.of(2021, 1, 1), LocalDate.of(2021, 12, 31));

    /** {@code perDay} repositories in every day; every call is recorded. */
    private static RepoCounter uniform(int perDay, List<DateRange> calls) {
        return range -> {
            calls.add(range);
            return (int) range.days() * perDay;
        };
    }

    @Test
    void rangeUnderThresholdIsNotSplit() {
        List<DateRange> calls = new ArrayList<>();
        DateRange range = new DateRange(LocalDate.of(2021, 3, 1), LocalDate.of(2021, 3, 10));
        SearchSlicer slicer = new SearchSlicer(uniform(50, calls));    // 10 days * 50 = 500

        List<DateRange> slices = slicer.slice(range);

        assertThat(slices).containsExactly(range);
        assertThat(calls).containsExactly(range);
    }

    @Test
    void exactlyThresholdIsNotSplit() {
        List<DateRange> calls = new ArrayList<>();
        DateRange range = new DateRange(LocalDate.of(2021, 3, 1), LocalDate.of(2021, 3, 10));
        SearchSlicer slicer = new SearchSlicer(uniform(100, calls));   // 10 days * 100 = 1000: the limit is inclusive

        assertThat(slicer.slice(range)).containsExactly(range);
        assertThat(calls).containsExactly(range);
    }

    @Test
    void monthIsSplitUntilEverySliceFits() {
        RepoCounter counter = uniform(100, new ArrayList<>());

        List<DateRange> slices = new SearchSlicer(counter).slice(MARCH);

        assertThat(slices).hasSizeGreaterThan(1)
                .allSatisfy(s -> assertThat(counter.count(s)).isLessThanOrEqualTo(SearchSlicer.MAX_RESULTS));
    }

    @Test
    void slicesCoverRangeWithoutGapsAndOverlaps() {
        List<DateRange> slices = new SearchSlicer(uniform(100, new ArrayList<>())).slice(YEAR);

        assertThat(slices.getFirst().from()).isEqualTo(YEAR.from());
        assertThat(slices.getLast().to()).isEqualTo(YEAR.to());
        for (int i = 1; i < slices.size(); i++) {
            assertThat(slices.get(i).from()).as("slice %d starts the day after slice %d ends", i, i - 1)
                    .isEqualTo(slices.get(i - 1).to().plusDays(1));
        }
        assertThat(slices.stream().mapToLong(DateRange::days).sum()).isEqualTo(365);
    }

    @Test
    void singleDayOverThresholdIsReturnedAsIs() {
        List<DateRange> calls = new ArrayList<>();
        DateRange day = new DateRange(LocalDate.of(2021, 3, 15), LocalDate.of(2021, 3, 15));
        SearchSlicer slicer = new SearchSlicer(uniform(5000, calls));

        assertThat(slicer.slice(day)).containsExactly(day);
        assertThat(calls).containsExactly(day);
    }

    @Test
    void spikeDayIsIsolated() {
        LocalDate spike = LocalDate.of(2021, 3, 15);
        RepoCounter counter = r -> (int) r.days() * 10
                + (!spike.isBefore(r.from()) && !spike.isAfter(r.to()) ? 5000 : 0);

        List<DateRange> slices = new SearchSlicer(counter).slice(MARCH);

        assertThat(slices).contains(new DateRange(spike, spike));
        assertThat(slices).filteredOn(s -> !s.isSingleDay() || !s.from().equals(spike))
                .allSatisfy(s -> assertThat(counter.count(s)).isLessThanOrEqualTo(SearchSlicer.MAX_RESULTS));
        assertThat(slices.getFirst().from()).isEqualTo(MARCH.from());
        assertThat(slices.getLast().to()).isEqualTo(MARCH.to());
    }

    @Test
    void countIsCalledOncePerNode() {
        // 31 days * 100 = 3100 -> 15 + 16 days (1500, 1600) -> 7 + 8 and 8 + 8 days: 4 leaves, 2 * 4 - 1 = 7 nodes
        List<DateRange> calls = new ArrayList<>();

        List<DateRange> slices = new SearchSlicer(uniform(100, calls)).slice(MARCH);

        assertThat(slices).extracting(DateRange::days).containsExactly(7L, 8L, 8L, 8L);
        assertThat(calls).hasSize(7).doesNotHaveDuplicates();
    }
}
