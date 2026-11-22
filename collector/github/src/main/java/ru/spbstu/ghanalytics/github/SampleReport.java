package ru.spbstu.ghanalytics.github;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The two files that describe a sample, as pure functions of the results plus one write.
 * <ul>
 *   <li>{@value #POPULATION_FILE}: {@code month,population}, a row per month with the month as its
 *       first day; exactly two columns, the table of weights is loaded from it;</li>
 *   <li>{@value #WINDOWS_FILE}: {@code month,window_from,window_to,repos}, the windows taken, in
 *       the order they were taken. A repository belongs to the sample if its {@code created_at} falls
 *       into one of them. A month taken whole gives no rows here.</li>
 * </ul>
 */
final class SampleReport {

    static final String POPULATION_FILE = "population-by-month.csv";
    static final String WINDOWS_FILE = "sample-windows.csv";

    private SampleReport() {
    }

    /** {@code 2018-01-01,19410}: the month of a clipped stratum ({@code 2018-01-15..2018-01-31}) is still its first day. */
    static String populationCsv(List<RepoCollector.StratumResult> results) {
        StringBuilder csv = new StringBuilder("month,population\n");
        for (RepoCollector.StratumResult r : results) {
            csv.append(RepoCollector.monthKey(r.stratum())).append(',').append(r.population()).append('\n');
        }
        return csv.toString();
    }

    /** {@code 2023-03-01,2023-03-15T10:00:00Z,2023-03-15T10:09:59Z,5}. */
    static String windowsCsv(List<RepoCollector.StratumResult> results) {
        StringBuilder csv = new StringBuilder("month,window_from,window_to,repos\n");
        for (RepoCollector.StratumResult r : results) {
            String month = RepoCollector.monthKey(r.stratum());
            for (RepoCollector.WindowResult w : r.windows()) {
                csv.append(month).append(',').append(w.window().from()).append(',').append(w.window().to())
                        .append(',').append(w.repos()).append('\n');
            }
        }
        return csv.toString();
    }

    /** Writes both files into {@code dir}, creating it, and replaces the files of an earlier run. */
    static void write(Path dir, List<RepoCollector.StratumResult> results) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(POPULATION_FILE), populationCsv(results), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve(WINDOWS_FILE), windowsCsv(results), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write the sample report to " + dir.toAbsolutePath(), e);
        }
    }
}
