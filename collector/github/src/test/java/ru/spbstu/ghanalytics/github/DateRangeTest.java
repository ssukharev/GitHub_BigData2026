package ru.spbstu.ghanalytics.github;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateRangeTest {

    private static DateRange range(String from, String to) {
        return new DateRange(LocalDate.parse(from), LocalDate.parse(to));
    }

    @Test
    void rejectsFromAfterTo() {
        assertThatThrownBy(() -> range("2021-03-02", "2021-03-01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2021-03-02");
        assertThatThrownBy(() -> new DateRange(null, LocalDate.of(2021, 3, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void daysCountsBothEnds() {
        assertThat(range("2021-03-01", "2021-03-01").days()).isEqualTo(1);
        assertThat(range("2021-03-01", "2021-03-31").days()).isEqualTo(31);
        assertThat(range("2020-01-01", "2020-12-31").days()).as("leap year").isEqualTo(366);
    }

    @Test
    void singleDayIsSingleDay() {
        assertThat(range("2021-03-01", "2021-03-01").isSingleDay()).isTrue();
        assertThat(range("2021-03-01", "2021-03-02").isSingleDay()).isFalse();
    }

    @Test
    void splitsTwoDays() {
        assertThat(range("2021-03-01", "2021-03-02").split())
                .containsExactly(range("2021-03-01", "2021-03-01"), range("2021-03-02", "2021-03-02"));
    }

    @Test
    void splitsOddRangeWithoutGapOrOverlap() {
        List<DateRange> halves = range("2021-03-01", "2021-03-31").split();

        assertThat(halves).containsExactly(range("2021-03-01", "2021-03-15"), range("2021-03-16", "2021-03-31"));
        assertThat(halves.get(0).days()).isLessThanOrEqualTo(halves.get(1).days());
        assertThat(halves.get(0).days() + halves.get(1).days()).isEqualTo(31);
    }

    @Test
    void singleDayCannotBeSplit() {
        assertThatThrownBy(() -> range("2021-03-01", "2021-03-01").split())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toQualifierUsesIsoDates() {
        assertThat(range("2021-03-01", "2021-03-31").toQualifier()).isEqualTo("created:2021-03-01..2021-03-31");
    }
}
