package ru.spbstu.ghanalytics.core.http;

import com.fasterxml.jackson.core.JacksonException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import ru.spbstu.ghanalytics.core.run.RunStats;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pauses are recorded, not slept: the whole class takes milliseconds although it "waits" over 20 seconds. */
class RetryPolicyTest {

    private final List<Duration> sleeps = new ArrayList<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final RetryPolicy policy =
            new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(30), sleeps::add, () -> 0.0);

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();   // an interrupt test must not break its neighbours
    }

    @Test
    void retriesIoExceptionAndReturnsResult() {
        String result = policy.execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new IOException("connection reset");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(3);
        assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1));
    }

    @Test
    void returnsImmediatelyOnSuccess() {
        String result = policy.execute(() -> {
            calls.incrementAndGet();
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void givesUpAfterMaxAttemptsOn503() {
        assertThatThrownBy(() -> policy.execute(() -> {
            calls.incrementAndGet();
            throw new RetryableHttpException(503, "HTTP 503", null);
        })).isInstanceOf(RetryableHttpException.class);

        assertThat(calls).hasValue(5);
        assertThat(sleeps).containsExactly(Duration.ofMillis(500), Duration.ofSeconds(1),
                Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void doesNotRetry404() {
        assertThatThrownBy(() -> policy.execute(() -> {
            calls.incrementAndGet();
            throw new HttpStatusException(404, "HTTP 404");
        })).isExactlyInstanceOf(HttpStatusException.class);

        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void respectsRetryAfter() {
        String result = policy.execute(() -> {
            if (calls.incrementAndGet() == 1) {
                throw new RetryableHttpException(429, "HTTP 429", Duration.ofSeconds(7));
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(sleeps).containsExactly(Duration.ofSeconds(7));
    }

    @Test
    void capsDelayAndAppliesJitter() {
        RetryPolicy capped = new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(3), sleeps::add, () -> 0.5);

        assertThatThrownBy(() -> capped.execute(() -> {
            throw new IOException("timeout");
        })).isInstanceOf(UncheckedIOException.class);

        assertThat(sleeps).containsExactly(Duration.ofMillis(750), Duration.ofMillis(1500),
                Duration.ofMillis(2250), Duration.ofMillis(2250));
    }

    @Test
    void wrapsIoExceptionWhenExhausted() {
        assertThatThrownBy(() -> policy.execute(() -> {
            calls.incrementAndGet();
            throw new IOException("connection reset");
        })).isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class)
                .hasRootCauseMessage("connection reset");

        assertThat(calls).hasValue(5);
    }

    @Test
    void stopsWhenInterrupted() {
        RetryPolicy interrupted = new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(30),
                d -> { throw new InterruptedException(); }, () -> 0.0);

        assertThatThrownBy(() -> interrupted.execute(() -> {
            calls.incrementAndGet();
            throw new IOException("connection reset");
        })).isInstanceOf(UncheckedIOException.class)
                .satisfies(e -> assertThat(e.getSuppressed()).singleElement().isInstanceOf(InterruptedException.class));

        assertThat(calls).hasValue(1);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void interruptInsideCallStopsAtOnce() {
        assertThatThrownBy(() -> policy.execute(() -> {
            calls.incrementAndGet();
            throw new InterruptedException();
        })).isInstanceOf(IllegalStateException.class)
                .hasCauseInstanceOf(InterruptedException.class);

        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    @Test
    void countsRetries() {
        RunStats stats = new RunStats();
        RetryPolicy counting = new RetryPolicy(5, Duration.ofSeconds(1), Duration.ofSeconds(30),
                sleeps::add, () -> 0.0, stats);

        counting.execute(() -> {
            if (calls.incrementAndGet() < 3) {
                throw new IOException("connection reset");
            }
            return "ok";
        });
        assertThat(stats.snapshot()).containsEntry(RetryPolicy.RETRIES, 2L);

        stats.reset();
        assertThatThrownBy(() -> counting.execute(() -> {
            throw new RetryableHttpException(503, "HTTP 503", null);
        })).isInstanceOf(RetryableHttpException.class);
        assertThat(stats.snapshot()).containsEntry(RetryPolicy.RETRIES, 4L);   // the 5th failure is not a retry
    }

    @Test
    void doesNotRetryBugs() {
        NullPointerException bug = new NullPointerException("field is null");

        assertThatThrownBy(() -> policy.execute(() -> {
            calls.incrementAndGet();
            throw bug;
        })).isSameAs(bug);

        assertThat(calls).hasValue(1);
    }

    @Test
    void doesNotRetryJsonParseErrors() {
        ObjectMapper mapper = new ObjectMapper();

        assertThatThrownBy(() -> policy.execute(() -> {
            calls.incrementAndGet();
            return mapper.readTree("<html>Bad gateway</html>");   // JacksonException is an IOException
        })).isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(JacksonException.class);

        assertThat(calls).hasValue(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void lateAttemptsStayAtCeiling() {
        assertThat(policy.delayFor(100, new IOException())).isEqualTo(Duration.ofSeconds(15));
    }

    @Test
    void rejectsInvalidSettings() {
        Duration second = Duration.ofSeconds(1);
        assertThatThrownBy(() -> new RetryPolicy(0, second, second, sleeps::add, () -> 0.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxAttempts");
        assertThatThrownBy(() -> new RetryPolicy(5, Duration.ZERO, second, sleeps::add, () -> 0.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("baseDelay");
        assertThatThrownBy(() -> new RetryPolicy(5, second, Duration.ofMillis(500), sleeps::add, () -> 0.0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("maxDelay");
    }
}
