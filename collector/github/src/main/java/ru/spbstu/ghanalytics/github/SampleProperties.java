package ru.spbstu.ghanalytics.github;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Settings {@code collector.sample.*} of the {@code github-repos} sample.
 * {@code --seed=N} on the command line is the short form of {@code --collector.sample.seed=N}
 * ({@code application.yml} maps it).
 *
 * @param seed      seed of the random window order: the same seed gives the same sample plan
 * @param reportDir where {@code population-by-month.csv} and {@code sample-windows.csv} are written,
 *                  relative to the working directory
 */
@ConfigurationProperties("collector.sample")
public record SampleProperties(
        @DefaultValue("42") long seed,
        @DefaultValue("docs/data") Path reportDir) {

    public SampleProperties {
        Objects.requireNonNull(reportDir, "reportDir");
    }
}
