package ru.spbstu.ghanalytics.analytics;

import ru.spbstu.ghanalytics.analytics.task1.Task1Main;
import ru.spbstu.ghanalytics.analytics.task1.Task1GhArchive;
import ru.spbstu.ghanalytics.analytics.task3.Task3Main;

import java.nio.file.Path;

/**
 * Result tables of tasks 1 and 3 from the database in {@code .env}: CSV to {@code results/taskN},
 * figures for the article (PNG) to {@code ../docs/figures}.
 * <pre>
 * cd analytics-java
 * mvn -q compile exec:java                                # Innovation Graph and World Bank
 * mvn -q compile exec:java -Dexec.args=task1               # Innovation Graph only
 * mvn -q compile exec:java -Dexec.args=task1-gharchive     # GH Archive separately
 * </pre>
 * Needs the corresponding data: Innovation Graph and World Bank, or the GH Archive snapshot.
 */
public final class Main {

    private static final Path FIGURES = Path.of("../docs/figures");

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        String task = args.length > 0 ? args[0] : "all";
        DbConfig db = DbConfig.fromEnvironment();
        switch (task) {
            case "task1" -> Task1Main.run(db, Path.of("results/task1"), FIGURES);
            case "task1-gharchive" -> Task1GhArchive.run(db, Path.of("results/task1-gharchive"), FIGURES);
            case "task3" -> Task3Main.run(db, Path.of("results/task3"), FIGURES);
            case "all" -> {
                Task1Main.run(db, Path.of("results/task1"), FIGURES);
                Task3Main.run(db, Path.of("results/task3"), FIGURES);
            }
            default -> throw new IllegalArgumentException(
                    "Unknown task '" + task + "', expected task1, task1-gharchive, task3 or all");
        }
    }
}
