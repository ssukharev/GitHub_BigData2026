package ru.spbstu.ghanalytics.github;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimeWindowTest {

    private static final Instant TEN = Instant.parse("2023-03-15T10:00:00Z");

    @Test
    void toQualifierUsesIsoInstants() {
        assertThat(new TimeWindow(TEN, TEN.plusSeconds(599)).toQualifier())
                .isEqualTo("created:2023-03-15T10:00:00Z..2023-03-15T10:09:59Z");
    }

    @Test
    void secondsCountsBothEnds() {
        assertThat(new TimeWindow(TEN, TEN.plusSeconds(599)).seconds()).isEqualTo(600);
        assertThat(new TimeWindow(TEN, TEN).seconds()).as("one second").isEqualTo(1);
    }

    @Test
    void rejectsFromAfterTo() {
        assertThatThrownBy(() -> new TimeWindow(TEN.plusSeconds(1), TEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2023-03-15T10:00:01Z");
        assertThatThrownBy(() -> new TimeWindow(null, TEN)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsFractionsOfASecond() {
        assertThatThrownBy(() -> new TimeWindow(TEN, TEN.plusMillis(599_500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole seconds");
    }

    @Test
    void containsBothEnds() {
        TimeWindow window = new TimeWindow(TEN, TEN.plusSeconds(599));

        assertThat(window.contains(TEN)).isTrue();
        assertThat(window.contains(TEN.plusSeconds(599))).isTrue();
        assertThat(window.contains(TEN.plusSeconds(600))).isFalse();
        assertThat(window.contains(TEN.minusSeconds(1))).isFalse();
    }
}
