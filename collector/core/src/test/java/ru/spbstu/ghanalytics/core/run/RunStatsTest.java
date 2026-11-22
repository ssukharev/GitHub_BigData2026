package ru.spbstu.ghanalytics.core.run;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

class RunStatsTest {

    private final RunStats stats = new RunStats();

    @Test
    void emptyByDefault() {
        assertThat(stats.snapshot()).isEmpty();
    }

    @Test
    void incAndAddAccumulateAsLongs() {
        stats.inc("retries");
        stats.inc("retries");
        stats.add("rate_limit_wait_s", 1805);
        stats.add("graphql_points", 0);

        assertThat(stats.snapshot()).containsExactly(
                entry("graphql_points", 0L),
                entry("rate_limit_wait_s", 1805L),
                entry("retries", 2L));
    }

    @Test
    void snapshotIsImmutableCopy() {
        stats.inc("http_5xx");
        Map<String, Long> before = stats.snapshot();

        stats.inc("http_5xx");

        assertThat(before).containsExactly(entry("http_5xx", 1L));
        assertThatThrownBy(() -> before.put("http_4xx", 1L)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void resetDropsCounters() {
        stats.inc("retries");

        stats.reset();

        assertThat(stats.snapshot()).isEmpty();
    }

    @Test
    void rejectsBlankKeyAndNegativeDelta() {
        assertThatThrownBy(() -> stats.inc(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> stats.add("rate_limit_wait_s", -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rate_limit_wait_s");
    }

    @Test
    void countsFromManyThreads() throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        for (int t = 0; t < 8; t++) {
            pool.execute(() -> {
                for (int i = 0; i < 10_000; i++) {
                    stats.inc("graphql_requests");
                }
            });
        }
        pool.shutdown();

        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        assertThat(stats.snapshot()).containsEntry("graphql_requests", 80_000L);
    }
}
