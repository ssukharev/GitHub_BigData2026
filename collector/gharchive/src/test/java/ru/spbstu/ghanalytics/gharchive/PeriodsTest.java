package ru.spbstu.ghanalytics.gharchive;

import org.junit.jupiter.api.Test;
import ru.spbstu.ghanalytics.gharchive.Periods.Chunk;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeriodsTest {

    private static LocalDate d(String s) {
        return LocalDate.parse(s);
    }

    private static Chunk chunk(String from, String to) {
        return new Chunk(d(from), d(to));
    }

    @Test
    void byYearCutsFirstAndLastYear() {
        assertThat(Periods.byYear(d("2018-03-10"), d("2019-02-20")))
                .containsExactly(chunk("2018-03-10", "2018-12-31"), chunk("2019-01-01", "2019-02-20"));
    }

    @Test
    void byYearOfWholeYearsAndOfOneDay() {
        assertThat(Periods.byYear(d("2018-01-01"), d("2026-07-31")))
                .hasSize(9)
                .startsWith(chunk("2018-01-01", "2018-12-31"))
                .endsWith(chunk("2026-01-01", "2026-07-31"));
        assertThat(Periods.byYear(d("2024-03-06"), d("2024-03-06"))).containsExactly(chunk("2024-03-06", "2024-03-06"));
    }

    @Test
    void fullMonthsDropsPartialMonthsAtBothEnds() {
        assertThat(Periods.fullMonths(d("2024-01-15"), d("2024-03-31"))).contains(chunk("2024-02-01", "2024-03-31"));
        assertThat(Periods.fullMonths(d("2018-03-10"), d("2018-12-31"))).contains(chunk("2018-04-01", "2018-12-31"));
        assertThat(Periods.fullMonths(d("2019-01-01"), d("2019-02-20"))).contains(chunk("2019-01-01", "2019-01-31"));
    }

    @Test
    void fullMonthsKnowsLeapFebruary() {
        assertThat(Periods.fullMonths(d("2024-02-01"), d("2024-02-29"))).contains(chunk("2024-02-01", "2024-02-29"));
        assertThat(Periods.fullMonths(d("2024-02-01"), d("2024-02-28"))).isEmpty();
    }

    @Test
    void fullMonthsOfOneDayOrPartOfMonthIsEmpty() {
        assertThat(Periods.fullMonths(d("2024-03-06"), d("2024-03-06"))).isEmpty();
        assertThat(Periods.fullMonths(d("2024-03-02"), d("2024-04-30"))).contains(chunk("2024-04-01", "2024-04-30"));
        assertThat(Periods.fullMonths(d("2024-03-02"), d("2024-04-29"))).isEmpty();
    }

    @Test
    void fromAfterToIsRejected() {
        assertThatThrownBy(() -> Periods.fullMonths(d("2024-03-07"), d("2024-03-06")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Periods.byYear(d("2024-03-07"), d("2024-03-06")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parameterValuesUseExclusiveEnd() {
        assertThat(Periods.startOf(d("2024-03-06"))).isEqualTo("2024-03-06 00:00:00");
        assertThat(Periods.endExclusive(d("2024-12-31"))).isEqualTo("2025-01-01 00:00:00");
    }
}
