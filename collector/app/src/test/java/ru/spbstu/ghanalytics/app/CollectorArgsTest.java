package ru.spbstu.ghanalytics.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectorArgsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

    private static CollectorArgs parse(String... args) {
        return CollectorArgs.parse(new DefaultApplicationArguments(args), TODAY);
    }

    @Test
    void usesDefaultsForOptionalArguments() {
        CollectorArgs args = parse("--source=worldbank");

        assertThat(args.source()).isEqualTo("worldbank");
        assertThat(args.context().from()).isEqualTo(CollectorArgs.DEFAULT_FROM);
        assertThat(args.context().to()).isEqualTo(TODAY);
        assertThat(args.context().minStars()).isZero();
        assertThat(args.context().limit()).isZero();
        assertThat(args.context().resume()).isFalse();
    }

    @Test
    void parsesAllOptions() {
        CollectorArgs args = parse("--source=github-repos", "--from=2024-01-01", "--to=2024-01-31",
                "--min-stars=10", "--limit=200", "--resume");

        assertThat(args.context().from()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(args.context().to()).isEqualTo(LocalDate.of(2024, 1, 31));
        assertThat(args.context().minStars()).isEqualTo(10);
        assertThat(args.context().limit()).isEqualTo(200);
        assertThat(args.context().resume()).isTrue();
    }

    @Test
    void leavesSpringPropertyOverridesToSpring() {
        CollectorArgs args = parse("--source=worldbank", "--spring.datasource.url=jdbc:postgresql://db/x");

        assertThat(args.source()).isEqualTo("worldbank");
    }

    @Test
    void rejectsUnknownOption() {
        assertThatThrownBy(() -> parse("--source=worldbank", "--limt=10"))
                .isInstanceOf(InvalidArgumentsException.class)
                .hasMessageContaining("--limt");
    }

    @Test
    void rejectsBadDate() {
        assertThatThrownBy(() -> parse("--source=worldbank", "--from=01.01.2024"))
                .isInstanceOf(InvalidArgumentsException.class)
                .hasMessageContaining("--from");
    }

    @Test
    void rejectsBadNumber() {
        assertThatThrownBy(() -> parse("--source=worldbank", "--limit=many"))
                .isInstanceOf(InvalidArgumentsException.class)
                .hasMessageContaining("--limit");
    }

    @Test
    void rejectsPeriodEndingBeforeItStarts() {
        assertThatThrownBy(() -> parse("--source=worldbank", "--from=2024-02-01", "--to=2024-01-01"))
                .isInstanceOf(InvalidArgumentsException.class)
                .hasMessageContaining("2024-02-01");
    }

    @Test
    void rejectsRepeatedOption() {
        assertThatThrownBy(() -> parse("--source=a", "--source=b"))
                .isInstanceOf(InvalidArgumentsException.class)
                .hasMessageContaining("2 times");
    }

    @Test
    void rejectsValueForFlag() {
        assertThatThrownBy(() -> parse("--source=worldbank", "--resume=yes"))
                .isInstanceOf(InvalidArgumentsException.class)
                .hasMessageContaining("--resume");
    }

    @Test
    void rejectsPositionalArguments() {
        assertThatThrownBy(() -> parse("--source=worldbank", "extra"))
                .isInstanceOf(InvalidArgumentsException.class)
                .hasMessageContaining("extra");
    }
}
