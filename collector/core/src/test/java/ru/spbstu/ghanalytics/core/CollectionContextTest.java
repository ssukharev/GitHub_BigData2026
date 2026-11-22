package ru.spbstu.ghanalytics.core;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class CollectionContextTest {

    private static final LocalDate JAN_1 = LocalDate.of(2019, 1, 1);
    private static final LocalDate JAN_31 = LocalDate.of(2019, 1, 31);

    @Test
    void rejectsFromAfterTo() {
        assertThatThrownBy(() -> new CollectionContext(JAN_31, JAN_1, 10, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2019-01-31");
    }

    @Test
    void acceptsSingleDay() {
        CollectionContext ctx = new CollectionContext(JAN_1, JAN_1, 10, 0, false);

        assertThat(ctx.from()).isEqualTo(ctx.to());
    }

    @Test
    void rejectsNullDates() {
        assertThatThrownBy(() -> new CollectionContext(null, JAN_31, 10, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CollectionContext(JAN_1, null, 10, 0, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNegativeNumbers() {
        assertThatThrownBy(() -> new CollectionContext(JAN_1, JAN_31, -1, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minStars");
        assertThatThrownBy(() -> new CollectionContext(JAN_1, JAN_31, 10, -1, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void zeroLimitMeansUnlimited() {
        assertThat(new CollectionContext(JAN_1, JAN_31, 10, 0, false).hasLimit()).isFalse();
        assertThat(new CollectionContext(JAN_1, JAN_31, 10, 200, false).hasLimit()).isTrue();
    }

    @Test
    void toParamsUsesCanonicalKeys() {
        CollectionContext ctx = new CollectionContext(JAN_1, JAN_31, 10, 5000, true);

        assertThat(ctx.toParams()).containsExactly(
                entry("from", "2019-01-01"),
                entry("to", "2019-01-31"),
                entry("min_stars", 10),
                entry("limit", 5000),
                entry("resume", true));
    }
}
