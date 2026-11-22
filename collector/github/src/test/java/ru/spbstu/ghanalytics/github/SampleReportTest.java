package ru.spbstu.ghanalytics.github;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SampleReportTest {

    private static final DateRange CLIPPED_JANUARY = new DateRange(LocalDate.of(2018, 1, 15), LocalDate.of(2018, 1, 31));
    private static final DateRange MARCH = new DateRange(LocalDate.of(2023, 3, 1), LocalDate.of(2023, 3, 31));

    private static TimeWindow window(String from) {
        Instant start = Instant.parse(from);
        return new TimeWindow(start, start.plusSeconds(599));
    }

    /** January taken whole (population under the quota), March by two windows. */
    private static final List<RepoCollector.StratumResult> RESULTS = List.of(
            new RepoCollector.StratumResult(CLIPPED_JANUARY, 40, 40, List.of(), 40),
            new RepoCollector.StratumResult(MARCH, 22197, 8, List.of(
                    new RepoCollector.WindowResult(window("2023-03-15T10:00:00Z"), 5),
                    new RepoCollector.WindowResult(window("2023-03-02T23:50:00Z"), 4)), 9));

    @Test
    void populationHasTwoColumnsAndTheFirstDayOfTheMonth() {
        assertThat(SampleReport.populationCsv(RESULTS)).isEqualTo("""
                month,population
                2018-01-01,40
                2023-03-01,22197
                """);
    }

    @Test
    void windowsInTheOrderTakenAndNoRowsForAWholeMonth() {
        assertThat(SampleReport.windowsCsv(RESULTS)).isEqualTo("""
                month,window_from,window_to,repos
                2023-03-01,2023-03-15T10:00:00Z,2023-03-15T10:09:59Z,5
                2023-03-01,2023-03-02T23:50:00Z,2023-03-02T23:59:59Z,4
                """);
    }

    @Test
    void emptyResultsGiveHeadersOnly() {
        assertThat(SampleReport.populationCsv(List.of())).isEqualTo("month,population\n");
        assertThat(SampleReport.windowsCsv(List.of())).isEqualTo("month,window_from,window_to,repos\n");
    }

    @Test
    void writeCreatesTheDirectoryAndReplacesOldFiles(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("docs/data");
        SampleReport.write(dir, List.of());
        SampleReport.write(dir, RESULTS);

        assertThat(Files.readString(dir.resolve("population-by-month.csv"))).isEqualTo(SampleReport.populationCsv(RESULTS));
        assertThat(Files.readAllLines(dir.resolve("sample-windows.csv"))).hasSize(3);
    }

    @Test
    void weightIsPopulationPerSampledRepository() {
        assertThat(RESULTS.get(1).weight()).isEqualTo(22197 / 9.0);
        assertThat(new RepoCollector.StratumResult(MARCH, 100, 0, List.of(), 0).weight()).isZero();
    }
}
