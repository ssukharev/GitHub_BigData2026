package ru.spbstu.ghanalytics.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import ru.spbstu.ghanalytics.core.CollectionContext;
import ru.spbstu.ghanalytics.core.Collector;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectorRunnerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC);

    /** Records the contexts it was called with; can be told to fail. */
    static final class FakeCollector implements Collector {
        private final String source;
        private final boolean fails;
        final List<CollectionContext> calls = new ArrayList<>();

        FakeCollector(String source, boolean fails) {
            this.source = source;
            this.fails = fails;
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public void collect(CollectionContext ctx) {
            calls.add(ctx);
            if (fails) {
                throw new IllegalStateException("source is down");
            }
        }
    }

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private CollectorRunner runner(Collector... collectors) {
        return new CollectorRunner(List.of(collectors), CLOCK, new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    private static DefaultApplicationArguments args(String... args) {
        return new DefaultApplicationArguments(args);
    }

    @Test
    void runsTheCollectorNamedBySource() {
        FakeCollector worldbank = new FakeCollector("worldbank", false);
        FakeCollector repos = new FakeCollector("github-repos", false);
        CollectorRunner runner = runner(worldbank, repos);

        runner.run(args("--source=github-repos", "--from=2024-01-01", "--limit=200"));

        assertThat(runner.getExitCode()).isEqualTo(CollectorRunner.OK);
        assertThat(worldbank.calls).isEmpty();
        assertThat(repos.calls).singleElement().satisfies(ctx -> {
            assertThat(ctx.from()).isEqualTo(LocalDate.of(2024, 1, 1));
            assertThat(ctx.to()).isEqualTo(LocalDate.of(2026, 11, 16));
            assertThat(ctx.limit()).isEqualTo(200);
        });
    }

    @Test
    void failingCollectorGivesExitCodeOne() {
        CollectorRunner runner = runner(new FakeCollector("gharchive", true));

        runner.run(args("--source=gharchive"));

        assertThat(runner.getExitCode()).isEqualTo(CollectorRunner.COLLECTOR_FAILED);
    }

    @Test
    void unknownSourceGivesExitCodeTwoAndRunsNothing() {
        FakeCollector worldbank = new FakeCollector("worldbank", false);
        CollectorRunner runner = runner(worldbank);

        runner.run(args("--source=worldbnak"));

        assertThat(runner.getExitCode()).isEqualTo(CollectorRunner.INVALID_ARGUMENTS);
        assertThat(worldbank.calls).isEmpty();
    }

    @Test
    void invalidArgumentsGiveExitCodeTwo() {
        FakeCollector worldbank = new FakeCollector("worldbank", false);
        CollectorRunner runner = runner(worldbank);

        runner.run(args("--source=worldbank", "--from=yesterday"));

        assertThat(runner.getExitCode()).isEqualTo(CollectorRunner.INVALID_ARGUMENTS);
        assertThat(worldbank.calls).isEmpty();
    }

    @Test
    void withoutSourcePrintsUsageWithAvailableSources() {
        CollectorRunner runner = runner(new FakeCollector("worldbank", false), new FakeCollector("gharchive", false));

        runner.run(args());

        assertThat(runner.getExitCode()).isEqualTo(CollectorRunner.OK);
        assertThat(output.toString(StandardCharsets.UTF_8))
                .contains("Usage:")
                .contains("Available sources: gharchive, worldbank");
    }

    @Test
    void duplicateSourceNamesAreRejectedAtStart() {
        assertThatThrownBy(() -> runner(new FakeCollector("worldbank", false), new FakeCollector("worldbank", false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("worldbank");
    }
}
