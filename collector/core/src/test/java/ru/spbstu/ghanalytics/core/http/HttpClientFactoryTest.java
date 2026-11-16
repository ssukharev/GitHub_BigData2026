package ru.spbstu.ghanalytics.core.http;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import ru.spbstu.ghanalytics.core.run.RunStats;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpClientFactoryTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(HttpClientFactory.class)
            .withBean(RunStats.class);

    @Test
    void createAppliesTimeoutHttp2AndRedirects() {
        HttpClient client = HttpClientFactory.create(Duration.ofSeconds(2));

        assertThat(client.connectTimeout()).contains(Duration.ofSeconds(2));
        assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_2);
        assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.NORMAL);
    }

    @Test
    void contextHasOneSharedClient() {
        context.run(ctx -> {
            assertThat(ctx).hasSingleBean(HttpClient.class);
            assertThat(ctx.getBean(HttpClient.class).connectTimeout()).contains(HttpClientFactory.CONNECT_TIMEOUT);
        });
    }

    /**
     * The bean must count into the RunStats bean and really sleep. An interrupted thread makes
     * Sleeper.SYSTEM throw at once, so this is checked without waiting.
     */
    @Test
    void retryPolicyBeanCountsInRunStatsBean() {
        context.run(ctx -> {
            RetryPolicy retry = ctx.getBean(RetryPolicy.class);
            RunStats stats = ctx.getBean(RunStats.class);
            try {
                Thread.currentThread().interrupt();

                assertThatThrownBy(() -> retry.execute(() -> {
                    throw new IOException("connection reset");
                })).isInstanceOf(UncheckedIOException.class);

                assertThat(Thread.currentThread().isInterrupted()).isTrue();
                assertThat(stats.snapshot()).containsEntry(RetryPolicy.RETRIES, 1L);
            } finally {
                Thread.interrupted();
            }
        });
    }
}
