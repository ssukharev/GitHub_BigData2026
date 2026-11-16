package ru.spbstu.ghanalytics.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the collector. Scans all packages under {@code ru.spbstu.ghanalytics},
 * so collectors from every module (core, github, gharchive, worldbank, ...) are found.
 * The process exit code comes from {@link CollectorRunner}: 0 success, 1 collector failed,
 * 2 invalid arguments or unknown source.
 */
@SpringBootApplication(scanBasePackages = "ru.spbstu.ghanalytics")
public class CollectorApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(CollectorApplication.class, args)));
    }
}
