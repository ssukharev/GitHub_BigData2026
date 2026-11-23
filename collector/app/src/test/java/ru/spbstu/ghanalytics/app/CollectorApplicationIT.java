package ru.spbstu.ghanalytics.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.spbstu.ghanalytics.core.run.CollectionRunService;
import ru.spbstu.ghanalytics.github.SampleProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the whole application the way {@code java -jar collector.jar --seed=7} does (without a source),
 * on a real PostgreSQL: catches wiring errors that unit tests cannot see.
 */
@SpringBootTest(args = "--seed=7")
@Testcontainers
class CollectorApplicationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    CollectorRunner runner;

    @Autowired
    CollectionRunService runs;

    @Autowired
    SampleProperties sample;

    @Test
    void contextStartsAndRunnerPrintsUsageWithoutSource() {
        assertThat(runner.getExitCode()).isEqualTo(CollectorRunner.OK);
        assertThat(runs).isNotNull();
    }

    @Test
    void shortSeedOptionSetsTheSampleSeed() {
        assertThat(sample.seed()).isEqualTo(7);
        assertThat(sample.reportDir()).hasToString("docs/data");
    }
}
