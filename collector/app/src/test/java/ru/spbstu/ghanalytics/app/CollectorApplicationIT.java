package ru.spbstu.ghanalytics.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.spbstu.ghanalytics.core.run.CollectionRunService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Starts the whole application the way {@code java -jar collector.jar} does (without arguments),
 * on a real PostgreSQL: catches wiring errors that unit tests cannot see.
 */
@SpringBootTest
@Testcontainers
class CollectorApplicationIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    CollectorRunner runner;

    @Autowired
    CollectionRunService runs;

    @Test
    void contextStartsAndRunnerPrintsUsageWithoutSource() {
        assertThat(runner.getExitCode()).isEqualTo(CollectorRunner.OK);
        assertThat(runs).isNotNull();
    }
}
