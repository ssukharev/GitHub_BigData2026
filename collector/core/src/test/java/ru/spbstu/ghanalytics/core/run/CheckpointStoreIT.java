package ru.spbstu.ghanalytics.core.run;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import ru.spbstu.ghanalytics.core.CoreTestApplication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = CoreTestApplication.class)
@Testcontainers
class CheckpointStoreIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Autowired
    CheckpointStore checkpoints;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM core.checkpoints");
    }

    @Test
    void unknownKeyIsEmpty() {
        assertThat(checkpoints.get("gharchive", "hourly_activity:PUSH")).isEmpty();
    }

    @Test
    void putThenGetReturnsLastValue() {
        checkpoints.put("gharchive", "k", "2024-12-31");
        checkpoints.put("gharchive", "k", "2025-12-31");

        assertThat(checkpoints.get("gharchive", "k")).contains("2025-12-31");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.checkpoints", Integer.class)).isEqualTo(1);
    }

    @Test
    void sameKeyOfAnotherSourceIsSeparate() {
        checkpoints.put("gharchive", "k", "a");
        checkpoints.put("github-repos", "k", "b");

        assertThat(checkpoints.get("gharchive", "k")).contains("a");
        assertThat(checkpoints.get("github-repos", "k")).contains("b");
    }

    @Test
    void rejectsBlankKeyAndNullValue() {
        assertThatThrownBy(() -> checkpoints.put("gharchive", " ", "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> checkpoints.put("gharchive", "k", null)).isInstanceOf(IllegalArgumentException.class);
    }
}
